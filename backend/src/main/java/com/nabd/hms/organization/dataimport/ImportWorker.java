package com.nabd.hms.organization.dataimport;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.clinical.api.ImportedAllergies;
import com.nabd.hms.organization.dataimport.ImportMapper.Rec;
import com.nabd.hms.organization.dataimport.ImportMapper.Result;
import com.nabd.hms.organization.dataimport.ImportRepository.Job;
import com.nabd.hms.patient.api.PatientDirectory;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ADM-012: background half of the import. A job is parsed and dry-run when it is created, and after a commit it creates
 * patients in chunks of 500, each chunk ONE transaction that also moves the staged rows to created/merged/skipped and
 * bumps last_chunk. A crash therefore loses at most the open chunk, and a restart simply selects the next 500 rows still
 * ready: no patient is created twice. The job row is locked per chunk, so two replicas never import the same rows.
 * A sweeper (every 30 s, ShedLock) resumes jobs nobody has touched for 60 s.
 */
@Component
public class ImportWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);
    static final int CHUNK = 500;
    private static final int MAX_FAILURES = 5;

    private final ImportPipeline pipeline;
    private final ImportRepository repo;
    private final TenantScope scope;
    private final TenantContext tenant;
    private final PatientDirectory patients;
    private final ImportedAllergies allergies;
    private final EventPublisher events;
    private final ClinicClock clock;
    private final JdbcTemplate jdbc;
    private final com.nabd.hms.organization.setup.SetupStepEvents stepEvents;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "adm-import-worker");
        t.setDaemon(true);
        return t;
    });

    ImportWorker(ImportPipeline pipeline, ImportRepository repo, TenantScope scope, TenantContext tenant, PatientDirectory patients,
                 ImportedAllergies allergies, EventPublisher events, ClinicClock clock, JdbcTemplate jdbc,
                 com.nabd.hms.organization.setup.SetupStepEvents stepEvents) {
        this.stepEvents = stepEvents;
        this.pipeline = pipeline;
        this.repo = repo;
        this.scope = scope;
        this.tenant = tenant;
        this.patients = patients;
        this.allergies = allergies;
        this.events = events;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    void kick(UUID tenantId, UUID jobId) {
        executor.submit(() -> process(tenantId, jobId, Integer.MAX_VALUE));
    }

    @Scheduled(fixedDelayString = "${app.import.sweep-interval-ms:30000}", initialDelayString = "${app.import.sweep-interval-ms:30000}")
    @SchedulerLock(name = "adm-import-sweeper", lockAtMostFor = "PT10M")
    public void sweep() {
        // shortcut: one cheap indexed query per tenant every 30 s; fine to a few thousand clinics, then move to a cross-tenant work queue
        for (UUID t : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                List<UUID> stale = scope.run(t, () -> repo.staleJobs(60));
                for (UUID job : stale) process(t, job, Integer.MAX_VALUE);
                scope.run(t, () -> { repo.purge(); });
            } catch (RuntimeException e) {
                log.warn("import sweep failed for a tenant: {}", e.getClass().getSimpleName()); // log-phi-ok: exception class only
            }
        }
    }

    /**
     * Runs the job's next steps for at most maxChunks chunks (tests stop a run early to simulate a crash). Returns the
     * job's status afterwards.
     */
    public String process(UUID tenantId, UUID jobId, int maxChunks) {
        try {
            String status = scope.run(tenantId, () -> repo.findJob(jobId, false).map(Job::status).orElse("gone"));
            if ("validating".equals(status)) {
                scope.run(tenantId, () -> pipeline.stageAndDryRun(jobId));
                return scope.run(tenantId, () -> repo.findJob(jobId, false).map(Job::status).orElse("gone"));
            }
            int chunks = 0;
            while ("importing".equals(status) && chunks < maxChunks) {
                status = scope.run(tenantId, () -> chunk(jobId));
                chunks++;
            }
            return status;
        } catch (RuntimeException e) {
            log.warn("import job {} step failed: {}", jobId, e.getClass().getSimpleName()); // log-phi-ok: exception class only, messages may echo row data
            scope.run(tenantId, () -> failure(jobId));
            return "importing";
        }
    }

    /** One chunk in one transaction. Returns the job status after it. */
    @Transactional(propagation = Propagation.MANDATORY) // marker: only called inside TenantScope.run
    private String chunk(UUID jobId) {
        Job job = repo.findJob(jobId, true).orElseThrow();
        if (!"importing".equals(job.status())) return job.status();
        List<ImportRepository.Row> rows = repo.nextChunk(jobId, CHUNK);
        if (rows.isEmpty()) {
            complete(job);
            return "done";
        }
        Map<String, Integer> idx = pipeline.columnIndex(job);
        String fmt = pipeline.dateFormat(job);
        String region = clock.region(tenant.current());
        java.time.LocalDate today = clock.today(tenant.current());
        Map<ImportRepository.Row, Rec> recs = new LinkedHashMap<>();
        Set<String> phones = new HashSet<>();
        for (ImportRepository.Row r : rows) {
            Result res = ImportMapper.normalize(pipeline.decode(jobId, r), idx, fmt, region, today);
            if (res.rec() == null) { // cannot happen after a dry run with the same mapping; refuse rather than guess
                repo.markRow(jobId, r.rowNo(), "error", res.errorCode(), null);
                continue;
            }
            recs.put(r, res.rec());
            phones.add(res.rec().phone());
        }
        Set<String> existing = patients.existingPhoneDobKeys(phones); // a patient added since the dry run: skip, never double
        for (var e : recs.entrySet()) {
            ImportRepository.Row r = e.getKey();
            Rec rec = e.getValue();
            boolean explicit = "duplicate".equals(r.outcome());
            UUID patientId;
            String outcome;
            if (!explicit && existing.contains(rec.phone() + "|" + rec.dob())) {
                repo.markRow(jobId, r.rowNo(), "skipped", "DUP_REGISTRY", null);
                continue;
            }
            UUID found = "merge".equals(r.decision()) ? patients.findByPhoneDob(rec.phone(), rec.dob()).orElse(null) : null;
            if (found != null) {
                patients.mergeImported(found, rec.oldRecordNo());
                patientId = found;
                outcome = "merged";
            } else {
                patientId = patients.createImported(rec.name(), rec.phone(), rec.dob(), rec.sex(), rec.oldRecordNo());
                outcome = "created";
            }
            if (!rec.allergies().isEmpty()) allergies.addUnconfirmed(patientId, rec.allergies(), job.requestedBy());
            repo.markRow(jobId, r.rowNo(), outcome, null, patientId);
        }
        repo.chunkDone(jobId, rows.size());
        return "importing";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    private void complete(Job job) {
        repo.skipUndecidedDuplicates(job.id());
        Map<String, Integer> c = repo.outcomeCounts(job.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", c.getOrDefault("created", 0));
        result.put("merged", c.getOrDefault("merged", 0));
        result.put("skipped", c.getOrDefault("skipped", 0));
        result.put("errors", c.getOrDefault("error", 0));
        repo.finish(job.id(), pipeline.toJson(result));
        pipeline.auditAs(job.requestedBy(), "import.completed", job.id(), result);
        Map<String, Object> ev = new LinkedHashMap<>(result);
        ev.put("jobId", job.id());
        ev.put("importType", job.importType());
        events.publish(tenant.current(), DomainEventType.ORGANIZATION_IMPORT_COMPLETED, job.id(), ev);
    }

    /** A chunk threw (its transaction rolled back, nothing half-written). Retry on the next sweep, give up after MAX_FAILURES. */
    @Transactional(propagation = Propagation.MANDATORY)
    private void failure(UUID jobId) {
        repo.findJob(jobId, true).ifPresent(j -> {
            int n = j.errorMessage() != null && j.errorMessage().startsWith("RETRY:") ? Integer.parseInt(j.errorMessage().substring(6)) + 1 : 1;
            repo.setStatus(jobId, n >= MAX_FAILURES ? "failed" : j.status(), n >= MAX_FAILURES ? "CHUNK_FAILED" : "RETRY:" + n);
            if (n >= MAX_FAILURES) stepEvents.changed(tenant.current(), "import", "failed", "CHUNK_FAILED"); // ADM-005: a failed commit attempt
        });
    }
}
