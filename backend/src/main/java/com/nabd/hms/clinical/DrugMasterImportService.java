package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.platform.PlatformAudit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CLN-019: the versioned import of the KB feed into the regional drug master. start -> (async) parse + stage + validate with a
 * dry-run diff -> activate (one database transaction, see V2180). Nothing of an import is visible to prescribers until
 * activate succeeds, so a failure half-way (bad record, crash) leaves the active version untouched; retrying the same file
 * after a failure starts a new import. Feeds are read only from {@code app.drug-master.feed-dir} (empty = importing off).
 * Deliberately not a database transaction around the whole run: 200k products must not hold one open for minutes.
 */
@Service
public class DrugMasterImportService {

    private static final Logger log = LoggerFactory.getLogger(DrugMasterImportService.class);
    private static final int BATCH = 1000;

    private final DrugMasterRepository repo;
    private final DrugFeedAdapter adapter;
    private final PlatformAudit platformAudit;
    private final EventPublisher events;
    private final TenantScope tenantScope;
    private final com.nabd.hms.platform.tenant.TenantRegionService regions;
    private final ObjectMapper mapper;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drug-master-import");
        t.setDaemon(true);
        return t;
    });
    private final TransactionTemplate tx;
    // not final: ReflectionTestUtils points it at a temp dir in the test
    private Path feedDir;

    DrugMasterImportService(DrugMasterRepository repo, DrugFeedAdapter adapter, PlatformAudit platformAudit, EventPublisher events,
                            TenantScope tenantScope, com.nabd.hms.platform.tenant.TenantRegionService regions, ObjectMapper mapper, PlatformTransactionManager txManager, @Value("${app.drug-master.feed-dir:}") String feedDir) {
        this.repo = repo;
        this.adapter = adapter;
        this.platformAudit = platformAudit;
        this.events = events;
        this.tenantScope = tenantScope;
        this.regions = regions;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.feedDir = feedDir.isBlank() ? null : Path.of(feedDir).toAbsolutePath().normalize();
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    public Map<String, Object> start(String feedUri, String kind, boolean dryRun, UUID operatorId, String ip) {
        Path file = resolve(feedUri);
        DrugFeedAdapter.Header header;
        String checksum;
        try {
            header = adapter.header(file);
            checksum = sha256(file);
        } catch (DrugFeedAdapter.FeedException e) {
            throw invalid("FEED_INVALID", e.getMessage());
        }
        if (!header.kind().equals(kind)) {
            throw invalid("FEED_INVALID", "The feed header kind does not match the requested kind.");
        }
        DrugMasterRepository.Started s = tx.execute(x -> repo.start(header.region(), kind, dryRun, checksum, "operator:" + operatorId));
        if (s.created()) {
            platformAudit.record(null, operatorId, ip, "drug_master.import_started", "drug_master_import", s.importId(),
                    Map.of("region", header.region(), "kind", kind, "dryRun", dryRun, "month", header.month(), "checksum", checksum));
            worker.submit(() -> run(s.importId(), file, header));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importId", s.importId());
        out.put("status", s.status());
        return out;
    }

    private void run(UUID importId, Path file, DrugFeedAdapter.Header header) {
        try {
            List<Map<String, Object>> batch = new ArrayList<>(BATCH);
            adapter.products(file, p -> {
                batch.add(p);
                if (batch.size() == BATCH) {
                    flush(importId, batch);
                }
            });
            flush(importId, batch);
            tx.executeWithoutResult(x -> repo.validate(importId, header.kb(), header.month(), json(header.classes())));
        } catch (RuntimeException e) {
            log.warn("drug master import {} failed", importId);
            String why = e instanceof DrugFeedAdapter.FeedException ? e.getMessage() : "import failed (" + e.getClass().getSimpleName() + ")";
            try {
                tx.executeWithoutResult(x -> repo.fail(importId, why));
            } catch (RuntimeException e2) {
                log.error("drug master import {} could not be marked failed", importId);
            }
        }
    }

    private void flush(UUID importId, List<Map<String, Object>> batch) {
        if (!batch.isEmpty()) {
            tx.executeWithoutResult(x -> repo.stage(importId, json(batch)));
            batch.clear();
        }
    }

    public Map<String, Object> status(UUID importId) {
        DrugMasterRepository.ImportRow r = tx.execute(x -> repo.findImport(importId)).orElseThrow(DrugMasterImportService::importNotFound);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importId", r.id());
        out.put("region", r.region());
        out.put("kind", r.kind());
        out.put("dryRun", r.dryRun());
        out.put("status", r.status());
        out.put("version", r.version());
        out.put("kb", r.kb());
        out.put("month", r.dataMonth());
        out.put("diff", r.diffJson() == null ? null : mapper.readValue(r.diffJson(), Map.class));
        out.put("error", r.error());
        return out;
    }

    /** Version switch. Idempotent: activating the already-active import returns its version again. */
    public Map<String, Object> activate(UUID importId, String csoAckRef, UUID operatorId, String ip) {
        DrugMasterRepository.ImportRow r = tx.execute(x -> repo.findImport(importId)).orElseThrow(DrugMasterImportService::importNotFound);
        DrugMasterRepository.Activated a = tx.execute(x -> repo.activate(importId, csoAckRef));
        switch (a.outcome()) {
            case "activated" -> { }
            case "already_active" -> {
                return Map.of("version", a.version());
            }
            case "cso_required" -> throw new ApiException(HttpStatus.CONFLICT, "CSO_ACK_REQUIRED", "CSO acknowledgement required",
                    "This import changes schedule, pregnancy category or ingredients of existing products; send csoAckRef.");
            case "not_found" -> throw importNotFound();
            default -> throw new ApiException(HttpStatus.CONFLICT, "IMPORT_INVALID", "Import cannot be activated",
                    "Only a validated, non-dry-run import based on the current active version can be activated (" + a.outcome() + ").");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("region", r.region());
        after.put("version", a.version());
        after.put("added", a.added());
        after.put("changed", a.changed());
        after.put("retired", a.retired());
        after.put("csoAckRef", csoAckRef == null || csoAckRef.isBlank() ? null : csoAckRef.trim());
        platformAudit.record(null, operatorId, ip, "drug_master.version_activated", "drug_master_import", importId, after);
        publish(r.region(), a);
        return Map.of("version", a.version());
    }

    /** One event per tenant of the region (the outbox is per tenant). Best effort: the data is already switched. */
    private void publish(String region, DrugMasterRepository.Activated a) {
        for (UUID tenant : regions.tenantIdsIn(region)) {
            try {
                tenantScope.run(tenant, () -> events.publish(tenant, DomainEventType.DRUG_MASTER_VERSION_ACTIVATED, tenant,
                        Map.of("region", region, "drugMasterVersion", a.version(), "added", a.added(), "changed", a.changed(),
                                "retired", a.retired())));
            } catch (RuntimeException e) {
                log.warn("drug master activation event not published for tenant {}", tenant);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Path resolve(String feedUri) {
        if (feedDir == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FEED_IMPORT_DISABLED", "Drug feed import is not configured",
                    "app.drug-master.feed-dir is not set in this cell.");
        }
        if (feedUri == null || !feedUri.startsWith("file:")) {
            throw invalid("FEED_UNSUPPORTED", "Only file:<name> feeds from the configured feed directory are supported.");
        }
        Path p = feedDir.resolve(feedUri.substring(5)).normalize();
        if (!p.startsWith(feedDir) || !Files.isRegularFile(p)) {
            throw invalid("FEED_INVALID", "Feed file not found in the feed directory.");
        }
        return p;
    }

    private static String sha256(Path file) {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw invalid("FEED_INVALID", "Feed file cannot be read.");
        }
    }

    private String json(Object o) {
        return mapper.writeValueAsString(o);
    }

    private static ApiException invalid(String slug, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, slug, "Feed invalid", detail);
    }

    private static ApiException importNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Import not found", "No such drug master import.");
    }
}
