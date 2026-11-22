package com.nabd.hms.organization.offboarding;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantLifecyclePort;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.dpa.SimplePdf;
import com.nabd.hms.organization.export.DataExportService;
import com.nabd.hms.shared.files.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ADM-016 (NFR-18, SYS-23): a leaving owner asks for the clinic's data to be deleted. The request needs a full export
 * downloaded in the last 14 days or a recorded waiver, the built-in Owner role and a step-up used once. The clinic moves to
 * 'offboarding' and keeps working for 7 days, during which the owner can cancel (back to the status it had). After that
 * {@link OffboardingJob} moves it to 'offboarded' (every session ends), deletes its stored files, destroys its data key, calls
 * erase_offboarded_tenant() (V3420: every clinic table, only for the clinic bound to the transaction) and issues a signed
 * certificate kept in master.deletion_certificates. Every step is written to the platform audit chain, which outlives the
 * clinic's own. The certificate, the audit entries and the event carry ids, dates and counts only.
 */
@Service
public class OffboardingService {

    private static final Logger log = LoggerFactory.getLogger(OffboardingService.class);

    static final String STEP_UP_TARGET = "offboarding";
    static final String CANCEL_STEP_UP_TARGET = "offboarding-cancel:"; // + the request id: a confirmation cancels that request only
    static final Duration COOLING_OFF = Duration.ofDays(7);
    static final Duration EXPORT_WINDOW = Duration.ofDays(14);
    static final Duration CERTIFICATE_DUE = Duration.ofDays(30);
    static final Duration BACKUP_PURGE = Duration.ofDays(35);
    private static final String WORKER = "Offboarding erasure worker";

    public record Requested(UUID requestId, String status, Instant coolingOffEndsAt) {
    }

    public record View(UUID requestId, String status, Instant requestedAt, Instant coolingOffEndsAt, Instant certificateDueBy, boolean waived, UUID exportId) {
    }

    /** request = the clinic's latest deletion request (null when none); recentExportId = a full export downloaded in the last 14 days. */
    public record Status(View request, UUID recentExportId) {
    }

    public record Cancelled(String status) {
    }

    public record Certificate(Map<String, Object> certificate, String signature, String alg, String kid, String sha256) {
    }

    /** One request the worker has to act on. */
    record Due(UUID id, boolean erased, UUID certificateId) {
    }

    record Issued(UUID certificateId, String sha256, String email) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final TenantLifecyclePort lifecycle;
    private final EventPublisher events;
    private final ObjectStorage storage;
    private final DeletionCertificateSigner signer;
    private final ObjectMapper json;
    private final String signerName;
    private final String apiBase;

    OffboardingService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, AuditService audit, ActorDirectory actors, TenantLifecyclePort lifecycle,
                       EventPublisher events, ObjectStorage storage, DeletionCertificateSigner signer, ObjectMapper json,
                       @Value("${app.deletion-cert.signer-name:}") String signerName, @Value("${app.api-base-url:http://localhost:8080}") String apiBase) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.lifecycle = lifecycle;
        this.events = events;
        this.storage = storage;
        this.signer = signer;
        this.json = json;
        this.signerName = signerName.trim();
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    // ---------------------------------------------------------------- owner: status, request, cancel

    @Transactional(readOnly = true)
    public Status status() {
        UUID tenant = tenantContext.current();
        View v = jdbc.query("SELECT id, status, requested_at, cooling_off_ends_at, waived, export_id FROM offboarding_requests WHERE tenant_id = ? ORDER BY requested_at DESC LIMIT 1",
                (rs, i) -> new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(3).toInstant().plus(CERTIFICATE_DUE), rs.getBoolean(5), rs.getObject(6, UUID.class)), tenant).stream().findFirst().orElse(null);
        return new Status(v, downloadedExport(tenant, null));
    }

    @Transactional
    public Requested request(UUID staffId, UUID stepUpId, UUID exportId, boolean waiveExport, String reason, String ip) {
        UUID tenant = tenantContext.current();
        Requested open = jdbc.query("SELECT id, status, cooling_off_ends_at FROM offboarding_requests WHERE tenant_id = ? AND status IN ('cooling_off', 'erasing')",
                (rs, i) -> new Requested(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant()), tenant).stream().findFirst().orElse(null);
        if (open != null) { // asked twice: the same request, nothing new recorded
            return open;
        }
        if (!jdbc.queryForList("SELECT 1 FROM offboarding_requests WHERE tenant_id = ? AND step_up_id = ?", tenant, stepUpId).isEmpty()) {
            throw stepUpUsed();
        }
        UUID export = downloadedExport(tenant, exportId);
        if (export == null && !waiveExport) {
            throw new ApiException(HttpStatus.CONFLICT, "export-required", "Export required",
                    "Download a full export made in the last 14 days first, or confirm that you do not need one.", Map.of("code", "EXPORT_REQUIRED"));
        }
        String previous = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant);
        lifecycle.transitionBy("offboarding", staffId, "ADM-016: deletion requested by the owner");
        UUID id = UUID.randomUUID();
        Instant ends = clock.now().plus(COOLING_OFF);
        String email = jdbc.queryForObject("SELECT email::text FROM staff WHERE id = ?", String.class, staffId);
        try {
            jdbc.update("INSERT INTO offboarding_requests (id, tenant_id, requested_by, export_id, waived, reason, previous_status, step_up_id, notify_email, cooling_off_ends_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?)", id, tenant, staffId, export, export == null, reason.trim(), previous, stepUpId, email, Timestamp.from(ends));
        } catch (DuplicateKeyException e) {
            throw stepUpUsed();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requestId", id.toString());
        after.put("exportId", export == null ? null : export.toString());
        after.put("exportWaived", export == null);
        after.put("coolingOffEndsAt", ends.toString());
        after.put("stepUpId", stepUpId.toString());
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), ip, "offboarding.requested", "offboarding_request", id, null, after);
        after.put("requestedBy", staffId.toString());
        audit.recordPlatform("system", null, "Clinic owner", "Owner", ip, "offboarding.requested", "tenant", tenant, null, after);
        return new Requested(id, "cooling_off", ends);
    }

    @Transactional
    public Cancelled cancel(UUID staffId, UUID stepUpId, UUID id, String ip) {
        UUID tenant = tenantContext.current();
        Map<String, Object> r = jdbc.queryForList("SELECT status, previous_status FROM offboarding_requests WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
        if ("cancelled".equals(r.get("status"))) { // cancelled twice: nothing more to do
            return new Cancelled("cancelled");
        }
        int n = jdbc.update("UPDATE offboarding_requests SET status = 'cancelled', cancelled_at = now(), cancel_step_up_id = ?, notify_email = NULL "
                + "WHERE tenant_id = ? AND id = ? AND status = 'cooling_off' AND cooling_off_ends_at > now()", stepUpId, tenant, id);
        if (n == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "past-cooling-off", "Too late to cancel",
                    "The 7-day cooling-off period has ended. The deletion can no longer be cancelled.", Map.of("code", "PAST_COOLING_OFF"));
        }
        if ("offboarding".equals(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant))) {
            lifecycle.restoreFromOffboarding((String) r.get("previous_status"), staffId, "ADM-016: deletion request cancelled by the owner");
        }
        Map<String, Object> after = Map.of("requestId", id.toString(), "restoredStatus", r.get("previous_status"), "stepUpId", stepUpId.toString());
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), ip, "offboarding.cancelled", "offboarding_request", id, null, after);
        audit.recordPlatform("system", null, "Clinic owner", "Owner", ip, "offboarding.cancelled", "tenant", tenant, null, after);
        return new Cancelled("cancelled");
    }

    /** The full export (every dataset) this clinic downloaded in the last 14 days: the one named, or the latest. Null when none. */
    private UUID downloadedExport(UUID tenant, UUID exportId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT j.id, array_to_string(j.datasets, ',') AS datasets FROM data_export_jobs j "
                + "WHERE j.tenant_id = ? AND j.status = 'done' AND (?::uuid IS NULL OR j.id = ?::uuid) AND EXISTS (SELECT 1 FROM audit_log a WHERE a.tenant_id = j.tenant_id "
                + "AND a.action = 'data_export.downloaded' AND a.entity_id = j.id AND a.created_at > ?) ORDER BY j.completed_at DESC",
                tenant, exportId, exportId, Timestamp.from(clock.now().minus(EXPORT_WINDOW)));
        return rows.stream().filter(r -> r.get("datasets") != null && DataExportService.isFull(List.of(((String) r.get("datasets")).split(","))))
                .map(r -> (UUID) r.get("id")).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- worker steps (each its own transaction bound to the clinic)

    /**
     * The clinic's request the worker must act on, or null. A request whose cooling-off has ended becomes 'erasing' and the
     * clinic 'offboarded' (all sessions end) in this one transaction; a cancel that commits first wins, a later one gets 409.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Due claim() {
        UUID tenant = tenantContext.current();
        Map<String, Object> r = jdbc.queryForList("SELECT id, status, requested_at, erased_at, certificate_id FROM offboarding_requests WHERE tenant_id = ? AND (status = 'erasing' "
                + "OR (status = 'cooling_off' AND cooling_off_ends_at <= now()) OR (status = 'done' AND notify_email IS NOT NULL)) FOR UPDATE", tenant).stream().findFirst().orElse(null);
        if (r == null) {
            return null;
        }
        UUID id = (UUID) r.get("id");
        if ("cooling_off".equals(r.get("status"))) {
            String status = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant);
            if (!"offboarding".equals(status) && !"offboarded".equals(status)) { // an operator took the clinic out of offboarding: the request no longer stands
                jdbc.update("UPDATE offboarding_requests SET status = 'cancelled', cancelled_at = now(), notify_email = NULL WHERE tenant_id = ? AND id = ?", tenant, id);
                audit.recordPlatform("system", null, WORKER, "System", null, "offboarding.cancelled", "tenant", tenant, null, Map.of("requestId", id.toString(), "tenantStatus", status));
                return null;
            }
            jdbc.update("UPDATE offboarding_requests SET status = 'erasing' WHERE tenant_id = ? AND id = ?", tenant, id);
            if ("offboarding".equals(status)) {
                lifecycle.transitionBy("offboarded", null, "ADM-016: cooling-off ended, erasure started");
            }
            audit.recordPlatform("system", null, WORKER, "System", null, "offboarding.erasure_started", "tenant", tenant, null, Map.of("requestId", id.toString()));
        }
        if (r.get("certificate_id") == null && ((Timestamp) r.get("requested_at")).toInstant().plus(CERTIFICATE_DUE).isBefore(clock.now())) {
            log.error("ALERT deletion certificate overdue: request {} of tenant {} is older than 30 days", id, tenant); // log-phi-ok: ids
        }
        return new Due(id, r.get("erased_at") != null, (UUID) r.get("certificate_id"));
    }

    /** Stored files and export archives of the clinic, removed before their rows are. Returns the objects deleted. */
    @Transactional(propagation = Propagation.MANDATORY)
    int deleteStoredObjects() {
        UUID tenant = tenantContext.current();
        int n = 0;
        for (Map<String, Object> f : jdbc.queryForList("SELECT storage_key, quarantine_key FROM file_objects WHERE tenant_id = ?", tenant)) {
            storage.delete(ObjectStorage.Bucket.CLEAN, (String) f.get("storage_key"));
            if (f.get("quarantine_key") != null) {
                storage.delete(ObjectStorage.Bucket.QUARANTINE, (String) f.get("quarantine_key"));
            }
            n++;
        }
        for (String key : jdbc.queryForList("SELECT object_key FROM data_export_jobs WHERE tenant_id = ? AND object_key IS NOT NULL", String.class, tenant)) {
            storage.delete(ObjectStorage.Bucket.CLEAN, key);
            n++;
        }
        return n;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void erase() {
        jdbc.execute("SET LOCAL statement_timeout = '30min'"); // the API role's 5 s is for requests, not for emptying a clinic
        jdbc.queryForObject("SELECT erase_offboarded_tenant()::text", String.class);
    }

    /** Builds, signs and stores the certificate once; publishes the event and closes the request. */
    @Transactional(propagation = Propagation.MANDATORY)
    Issued certify(UUID requestId) {
        UUID tenant = tenantContext.current();
        Map<String, Object> r = jdbc.queryForMap("SELECT requested_at, erased_at, row_counts::text AS row_counts, waived, certificate_id, notify_email FROM offboarding_requests "
                + "WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, requestId);
        String email = (String) r.get("notify_email");
        if (r.get("certificate_id") != null) {
            UUID existing = (UUID) r.get("certificate_id");
            return new Issued(existing, jdbc.queryForObject("SELECT doc_sha256 FROM master.deletion_certificates WHERE tenant_id = ? AND id = ?", String.class, tenant, existing), email);
        }
        if (r.get("erased_at") == null) {
            throw new IllegalStateException("the clinic is not erased yet");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM tenant_data_keys WHERE tenant_id = ? AND status <> 'destroyed'", Integer.class, tenant) > 0) {
            throw new IllegalStateException("the clinic's data key is not destroyed yet");
        }
        int keys = jdbc.queryForObject("SELECT count(*) FROM tenant_data_keys WHERE tenant_id = ?", Integer.class, tenant);
        Map<String, Object> t = jdbc.queryForMap("SELECT name::text AS name, slug::text AS slug, brand_id, region_code::text AS region_code FROM tenants WHERE id = ?", tenant);
        Instant requested = ((Timestamp) r.get("requested_at")).toInstant().truncatedTo(ChronoUnit.SECONDS);
        Instant erased = ((Timestamp) r.get("erased_at")).toInstant().truncatedTo(ChronoUnit.SECONDS);
        Instant purge = erased.plus(BACKUP_PURGE);
        Map<String, Object> counts = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, Object> e : readMap((String) r.get("row_counts")).entrySet()) {
            long rows = ((Number) e.getValue()).longValue();
            counts.put(e.getKey(), rows);
            total += rows;
        }
        UUID id = UUID.randomUUID();
        Map<String, Object> signerBlock = new LinkedHashMap<>();
        signerBlock.put("organisation", "Nabd");
        signerBlock.put("role", "Data Protection Officer");
        if (!signerName.isEmpty()) {
            signerBlock.put("name", signerName);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "nabd.deletion-certificate.v1");
        doc.put("certificateId", id.toString());
        doc.put("tenant", Map.of("id", tenant.toString(), "name", t.get("name"), "slug", t.get("slug")));
        doc.put("brandId", t.get("brand_id") == null ? null : t.get("brand_id").toString());
        doc.put("branches", List.of(Map.of("id", tenant.toString(), "name", t.get("name")))); // one clinic = one branch (ADM-015 exports one branch per job too)
        doc.put("region", t.get("region_code"));
        doc.put("requestedAt", requested.toString());
        doc.put("erasedAt", erased.toString());
        doc.put("backupPurgeBy", purge.toString());
        doc.put("exportWaived", r.get("waived"));
        doc.put("dataKeysDestroyed", keys);
        doc.put("rowsErased", total);
        doc.put("rowCounts", counts);
        doc.put("signer", signerBlock);
        doc.put("statement", "All live records of this clinic were erased from Nabd on erasedAt and its data encryption key was destroyed. "
                + "Backups that still hold a copy expire by backupPurgeBy and cannot be read without the destroyed key.");
        DeletionCertificateSigner.Signed s = signer.sign(doc);
        jdbc.update("INSERT INTO master.deletion_certificates (id, tenant_id, brand_id, request_id, requested_at, erased_at, backup_purge_at, region, row_counts, signed_doc, signature, kid, doc_sha256) "
                        + "VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)", id, tenant, t.get("brand_id"), requestId, Timestamp.from(requested), Timestamp.from(erased), Timestamp.from(purge),
                t.get("region_code"), json.writeValueAsString(counts), s.canonical(), s.signature(), s.kid(), s.sha256());
        jdbc.update("UPDATE offboarding_requests SET status = 'done', certificate_id = ? WHERE tenant_id = ? AND id = ?", id, tenant, requestId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("certificateId", id);
        data.put("erasedAt", erased.toString());
        data.put("rowCounts", Map.of("tables", counts.size(), "rows", total)); // totals: the per-table counts are in the certificate (the envelope stays under 2 KB)
        events.publish(tenant, DomainEventType.ORGANIZATION_OFFBOARDING_ERASED, requestId, data);
        audit.recordPlatform("system", null, WORKER, "System", null, "offboarding.erased", "tenant", tenant, null,
                Map.of("requestId", requestId.toString(), "certificateId", id.toString(), "rowsErased", total, "dataKeysDestroyed", keys, "kid", s.kid()));
        return new Issued(id, s.sha256(), email);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void notified(UUID requestId) {
        jdbc.update("UPDATE offboarding_requests SET notify_email = NULL WHERE tenant_id = ? AND id = ?", tenantContext.current(), requestId);
    }

    String certificateUrl(Issued c) {
        return apiBase + "/v1/public/deletion-certificates/" + c.certificateId() + "/" + c.sha256();
    }

    // ---------------------------------------------------------------- reading a certificate (operator, or the holder of id + hash)

    /** sha256 null = an operator; otherwise it must equal the certificate's hash (404 like an unknown id when it does not). */
    public Certificate certificate(UUID id, String sha256) {
        Map<String, Object> c = jdbc.queryForList("SELECT signed_doc, signature, kid, doc_sha256 FROM deletion_certificate(?)", id).stream().findFirst().orElseThrow(ApiException::notFound);
        String actual = (String) c.get("doc_sha256");
        if (sha256 != null && !MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), sha256.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.notFound();
        }
        return new Certificate(readMap((String) c.get("signed_doc")), (String) c.get("signature"), "ES256", (String) c.get("kid"), actual);
    }

    public Map<String, Object> publicKeys() {
        return signer.publicJwks();
    }

    @SuppressWarnings("unchecked")
    public byte[] pdf(Certificate c) {
        Map<String, Object> d = c.certificate();
        Map<String, Object> tenant = (Map<String, Object>) d.get("tenant");
        Map<String, Object> by = (Map<String, Object>) d.get("signer");
        List<String> lines = new ArrayList<>(List.of("# Nabd data deletion certificate", "Certificate: " + d.get("certificateId"), "Clinic: " + tenant.get("name") + " (" + tenant.get("id") + ")",
                "Region: " + d.get("region"), "Deletion requested: " + d.get("requestedAt"), "Live data erased: " + d.get("erasedAt"), "Backups purged by: " + d.get("backupPurgeBy"),
                "Data encryption keys destroyed: " + d.get("dataKeysDestroyed"), "Rows erased: " + d.get("rowsErased"), "", "## Branches"));
        ((List<Map<String, Object>>) d.get("branches")).forEach(b -> lines.add(b.get("name") + " (" + b.get("id") + ")"));
        lines.add("");
        lines.add("## Rows erased per table");
        ((Map<String, Object>) d.get("rowCounts")).forEach((table, n) -> lines.add(table + ": " + n));
        lines.add("");
        lines.add("## Signature");
        lines.add("Signed by: " + (by.get("name") == null ? "" : by.get("name") + ", ") + by.get("role") + ", " + by.get("organisation"));
        lines.add("Algorithm: " + c.alg() + ", key " + c.kid());
        lines.add("SHA-256 of the signed certificate: " + c.sha256());
        lines.add("Signature: " + c.signature());
        lines.add("Verify: canonicalise the JSON certificate (RFC 8785) and check the signature with Nabd's public key.");
        return SimplePdf.render(lines);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String text) {
        return text == null ? Map.of() : json.readValue(text, Map.class);
    }

    private static ApiException stepUpUsed() {
        return new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required", "This confirmation was already used; confirm again.",
                Map.of("code", "STEP_UP_REQUIRED"));
    }
}
