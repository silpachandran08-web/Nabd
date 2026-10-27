package com.nabd.hms.organization.trial;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.patient.api.SyntheticPatients;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ADM-004: the background half of "Start for real". Deletes the clinic's demo patients and everything that depends on them in
 * chunks of 1,000 patients, one transaction per chunk; the running total is stored with each chunk, so a crash loses at most the
 * open chunk and the sweeper (every 30 s, ShedLock) resumes a job nobody is running. Audited with counts only.
 */
@Component
public class TrialConversionWorker {

    private static final Logger log = LoggerFactory.getLogger(TrialConversionWorker.class);
    static final int CHUNK = 1000;

    private final ClinicTrialRepository repo;
    private final SyntheticPatients synthetic;
    private final TenantScope scope;
    private final AuditService audit;
    private final EventPublisher events;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "adm-trial-conversion");
        t.setDaemon(true);
        return t;
    });

    TrialConversionWorker(ClinicTrialRepository repo, SyntheticPatients synthetic, TenantScope scope, AuditService audit, EventPublisher events) {
        this.repo = repo;
        this.synthetic = synthetic;
        this.scope = scope;
        this.audit = audit;
        this.events = events;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    void kick(UUID tenantId, UUID jobId) {
        executor.submit(() -> process(tenantId, jobId));
    }

    // shortcut: one cheap indexed query per trialing tenant every 30 s; fine to a few thousand clinics, then a cross-tenant work queue
    @Scheduled(fixedDelayString = "${app.trial.conversion-sweep-ms:30000}", initialDelayString = "${app.trial.conversion-sweep-ms:30000}")
    @SchedulerLock(name = "adm-trial-conversion-sweeper", lockAtMostFor = "PT10M")
    public void sweep() {
        for (UUID tenant : repo.trialingTenants()) {
            try {
                for (UUID job : scope.run(tenant, () -> repo.openJobs())) {
                    process(tenant, job);
                }
            } catch (RuntimeException e) {
                log.warn("trial conversion sweep failed for tenant {}", tenant);
            }
        }
    }

    /** Runs one job to the end; safe to call again for a job another thread is not running. Returns demo rows deleted in total. */
    public long process(UUID tenantId, UUID jobId) {
        try {
            if (!Boolean.TRUE.equals(scope.run(tenantId, () -> repo.markRunning(jobId)))) {
                return 0;
            }
            while (true) {
                SyntheticPatients.Batch b = scope.run(tenantId, () -> {
                    SyntheticPatients.Batch batch = synthetic.purgeBatch(CHUNK);
                    repo.addRows(jobId, batch.rows());
                    return batch;
                });
                if (b.patients() < CHUNK) {
                    break;
                }
            }
            return scope.run(tenantId, () -> {
                long rows = repo.rowsSoFar(jobId);
                if (!repo.finish(jobId)) {
                    return rows; // another thread finished it first
                }
                audit.record(tenantId, "system", null, "System", "System", null, "setup.trial_converted", "trial_conversion", jobId, null,
                        Map.of("syntheticRowsDeleted", rows));
                events.publish(tenantId, DomainEventType.ORGANIZATION_CONFIG_CHANGED, tenantId, Map.of("configKey", "trial.converted", "status", "updated"));
                log.info("trial conversion {} of tenant {} done: {} rows", jobId, tenantId, rows);
                return rows;
            });
        } catch (RuntimeException e) {
            log.warn("trial conversion {} of tenant {} failed", jobId, tenantId);
            String errorCode = e.getClass().getSimpleName();
            scope.run(tenantId, () -> repo.fail(jobId, errorCode));
            return 0;
        }
    }
}
