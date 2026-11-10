package com.nabd.hms.organization.export;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.dpa.SimplePdf;
import com.nabd.hms.shared.files.ObjectStorage;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
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
import java.security.SecureRandom;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-015 (SYS-23/NFR-18): the owner's full export. Start needs setup:export (owners only) and a step-up whose jti is stored once on
 * the job; one export runs at a time per clinic. {@link DataExportJob} builds the archive in the worker under the clinic's own
 * tenant context (no RLS bypass), one dataset per transaction so the drawer sees progress, writes a manifest (rows, bytes, SHA-256
 * per file), encrypts it with the tenant key and stores it. The owner gets a WhatsApp and an email with a link that needs an OTP to
 * the owner's mobile and stops after 72 h or 3 downloads. Start, denial and every download are audited; logs and notification
 * bodies carry no PHI.
 */
@Service
public class DataExportService {

    static final Map<String, List<String>> DATASETS = new LinkedHashMap<>();
    static {
        DATASETS.put("patients", List.of("patients", "patient_proxies"));
        DATASETS.put("appointments", List.of("appointments"));
        DATASETS.put("visits", List.of("queue_entries", "clinical_notes", "clinical_documents"));
        DATASETS.put("prescriptions", List.of("prescriptions", "prescription_items"));
        DATASETS.put("results", List.of("vitals"));
        DATASETS.put("invoices", List.of("invoices", "invoice_line_items", "invoice_payments"));
        DATASETS.put("consents", List.of("consents"));
        DATASETS.put("audit", List.of("audit_log"));
        DATASETS.put("files", List.of("file_objects"));
    }
    static final Set<String> FORMATS = Set.of("xlsx", "csv", "fhir_ndjson", "pdf_per_patient");
    static final String STEP_UP_TARGET = "data-export";
    static final Duration LINK_TTL = Duration.ofHours(72);
    static final int MAX_DOWNLOADS = 3;
    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration OTP_GAP = Duration.ofSeconds(30);
    static final int OTP_MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record StartRequest(String scope, List<String> datasets, String format, List<UUID> branchIds) {
    }

    public record Started(UUID id, String status, int estimateMinutes) {
    }

    public record DatasetProgress(String name, Long rows, String status) {
    }

    public record Job(UUID id, String status, String format, List<DatasetProgress> datasets, boolean linkSent, Instant linkExpiresAt,
                      Long bytes, String archiveSha256, Instant createdAt, Instant completedAt) {
    }

    record Claimed(UUID id, String format, List<String> datasets, UUID requestedBy) {
    }

    record Ready(String token, String email, String mobile) {
    }

    public record LinkView(String clinicName, Instant expiresAt, int downloadsLeft) {
    }

    public record Otp(String phone, String code) {
    }

    public enum Outcome { OK, EXPIRED, OTP_INVALID }

    public record Download(Outcome outcome, byte[] archive) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;
    private final NotificationPort notifications;
    private final ObjectStorage storage;
    private final FieldCipher cipher;
    private final ObjectMapper json;
    private final String webBase;

    DataExportService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, AuditService audit, ActorDirectory actors, EventPublisher events,
                      NotificationPort notifications, ObjectStorage storage, FieldCipher cipher, ObjectMapper json,
                      @Value("${app.patient-web-base-url:http://localhost:3810}") String webBase) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
        this.notifications = notifications;
        this.storage = storage;
        this.cipher = cipher;
        this.json = json;
        this.webBase = webBase.replaceAll("/+$", "");
    }

    // ---------------------------------------------------------------- owner: start, progress

    @Transactional
    public Started start(UUID staffId, UUID stepUpId, StartRequest r, String ip) {
        UUID tenant = tenantContext.current();
        if (r.scope() != null && !"full_tenant".equals(r.scope())) {
            throw invalid("scope must be full_tenant.");
        }
        if (!FORMATS.contains(r.format())) {
            throw invalid("format must be xlsx, csv, fhir_ndjson or pdf_per_patient.");
        }
        List<String> datasets = r.datasets() == null || r.datasets().isEmpty() ? List.copyOf(DATASETS.keySet()) : List.copyOf(new java.util.LinkedHashSet<>(r.datasets()));
        if (!DATASETS.keySet().containsAll(datasets)) {
            throw invalid("datasets must be among " + String.join(", ", DATASETS.keySet()) + ".");
        }
        // shortcut: one branch per export (the caller's own clinic); exporting several owned branches in one job is a follow-up
        if (r.branchIds() != null && r.branchIds().stream().anyMatch(b -> !tenant.equals(b))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "branch-not-allowed", "Branch not allowed", "Sign in to each branch to export it.", Map.of("code", "BRANCH_NOT_ALLOWED"));
        }
        if (!jdbc.queryForList("SELECT 1 FROM data_export_jobs WHERE step_up_id = ?", stepUpId).isEmpty()) { // replayed confirmation, whatever else is true
            throw new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required", "This confirmation was already used; confirm again.", Map.of("code", "STEP_UP_REQUIRED"));
        }
        if (!jdbc.queryForList("SELECT 1 FROM data_export_jobs WHERE tenant_id = ? AND format IS NOT NULL AND status IN ('pending','running')", tenant).isEmpty()) {
            throw running();
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        datasets.forEach(d -> progress.put(d, Map.of("status", "queued")));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO data_export_jobs (id, tenant_id, export_type, status, requested_by, datasets, format, progress, step_up_id) "
                            + "VALUES (?,?, 'full_tenant', 'running', ?, ?, ?, ?::jsonb, ?)",
                    id, tenant, staffId, datasets.toArray(new String[0]), r.format(), json.writeValueAsString(progress), stepUpId);
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("uq_data_export_jobs_step_up")) {
                throw new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required", "This confirmation was already used; confirm again.", Map.of("code", "STEP_UP_REQUIRED"));
            }
            throw running();
        }
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), ip, "data_export.started", "data_export_job", id, null,
                Map.of("format", r.format(), "datasets", String.join(",", datasets), "stepUpId", stepUpId.toString()));
        Integer patients = jdbc.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Integer.class, tenant);
        return new Started(id, "running", 1 + patients / 2000);
    }

    @Transactional
    public Job get(UUID id) {
        Map<String, Object> r = jdbc.queryForList("SELECT status, format, array_to_string(datasets, ',') AS datasets, progress::text AS progress, manifest::text AS manifest, "
                        + "link_id, bytes, created_at, completed_at FROM data_export_jobs WHERE tenant_id = ? AND id = ? AND format IS NOT NULL", tenantContext.current(), id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
        List<DatasetProgress> ds = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = r.get("progress") == null ? Map.of() : json.readValue((String) r.get("progress"), Map.class);
        for (String name : ((String) r.get("datasets")).split(",")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) progress.getOrDefault(name, Map.of());
            ds.add(new DatasetProgress(name, p.get("rows") == null ? null : ((Number) p.get("rows")).longValue(), (String) p.getOrDefault("status", "queued")));
        }
        Timestamp linkExp = r.get("link_id") == null ? null : jdbc.queryForObject("SELECT expires_at FROM data_export_links WHERE id = ?", Timestamp.class, r.get("link_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = r.get("manifest") == null ? Map.of() : json.readValue((String) r.get("manifest"), Map.class);
        return new Job(id, (String) r.get("status"), (String) r.get("format"), ds, r.get("link_id") != null, linkExp == null ? null : linkExp.toInstant(),
                r.get("bytes") == null ? null : ((Number) r.get("bytes")).longValue(), (String) manifest.get("archiveSha256"),
                ((Timestamp) r.get("created_at")).toInstant(), r.get("completed_at") == null ? null : ((Timestamp) r.get("completed_at")).toInstant());
    }

    @Transactional
    public void denied(UUID staffId, String ip) {
        UUID tenant = tenantContext.current();
        ActorDirectory.Actor a = actors.findActor(tenant, staffId).orElse(null);
        audit.record(tenant, "staff", staffId, a == null ? "unknown" : a.name(), a == null ? null : a.role(), ip, "data_export.denied", "data_export_job", null, null,
                Map.of("reason", "missing setup:export"));
    }

    // ---------------------------------------------------------------- worker steps (each its own tenant transaction)

    @Transactional(propagation = Propagation.MANDATORY)
    Claimed claimNext() {
        return jdbc.query("UPDATE data_export_jobs SET started_at = now() WHERE id = (SELECT id FROM data_export_jobs WHERE tenant_id = ? AND format IS NOT NULL "
                        + "AND status = 'running' AND started_at IS NULL ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING id, format, datasets, requested_by",
                (rs, i) -> new Claimed(rs.getObject(1, UUID.class), rs.getString(2), List.of((String[]) rs.getArray(3).getArray()), rs.getObject(4, UUID.class)),
                tenantContext.current()).stream().findFirst().orElse(null);
    }

    /** Writes one dataset into the archive and records its row count. */
    @Transactional(propagation = Propagation.MANDATORY)
    void writeDataset(Claimed job, String dataset, ExportArchive zip, Map<String, Map<String, Long>> counts) {
        UUID tenant = tenantContext.current();
        Map<String, Long> tables = new LinkedHashMap<>();
        List<ExportArchive.Table> sheets = new ArrayList<>();
        long rows = 0;
        boolean fhir = "fhir_ndjson".equals(job.format());
        boolean pdf = "pdf_per_patient".equals(job.format());
        for (String table : DATASETS.get(dataset)) {
            ExportArchive.Table t = read(tenant, table);
            tables.put(table, (long) t.rows().size());
            rows += t.rows().size();
            if ("xlsx".equals(job.format())) {
                sheets.add(t);
            } else if (!(fhir && fhirCovered(table)) && !pdf) {
                zip.csv((fhir ? "tables/" : "") + dataset + "/" + table + ".csv", t);
            }
        }
        if (!sheets.isEmpty()) {
            zip.xlsx(dataset + ".xlsx", sheets);
        }
        if (fhir) {
            fhir(tenant, dataset, zip);
        }
        if (pdf && "patients".equals(dataset)) {
            patientPdfs(tenant, zip);
        }
        if ("files".equals(dataset)) {
            files(tenant, zip);
        }
        counts.put(dataset, tables);
        progress(job.id(), dataset, rows, "done");
    }

    /** Stores the encrypted archive, opens the link, notifies the owner (WhatsApp here, email by the caller after commit). */
    @Transactional(propagation = Propagation.MANDATORY)
    Ready finish(Claimed job, ExportArchive zip, Map<String, Map<String, Long>> counts) {
        UUID tenant = tenantContext.current();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("exportId", job.id().toString());
        manifest.put("format", job.format());
        manifest.put("createdAt", clock.now().toString());
        manifest.put("datasets", counts);
        manifest.put("files", zip.entries().stream().map(ExportArchive::manifestEntry).toList());
        String manifestJson = json.writeValueAsString(manifest);
        byte[] archive = zip.finish(manifestJson);
        manifest.put("archiveSha256", ExportArchive.sha256(archive));
        String key = "tenant/" + tenant + "/exports/" + job.id() + ".zip.enc";
        storage.put(ObjectStorage.Bucket.CLEAN, key, cipher.encrypt(archive, aad(tenant, job.id())));
        String token = OpaqueTokens.generate();
        UUID link = UUID.randomUUID();
        jdbc.update("INSERT INTO data_export_links (id, tenant_id, job_id, token_hash, recipient_staff_id, expires_at, max_downloads) VALUES (?,?,?,?,?,?,?)",
                link, tenant, job.id(), OpaqueTokens.sha256Hex(token), job.requestedBy(), Timestamp.from(clock.now().plus(LINK_TTL)), MAX_DOWNLOADS);
        manifest.remove("files"); // the full list lives in the archive; the row keeps the counts and the archive checksum
        jdbc.update("UPDATE data_export_jobs SET status = 'done', manifest = ?::jsonb, object_key = ?, link_id = ?, bytes = ?, completed_at = now() WHERE id = ?",
                json.writeValueAsString(manifest), key, link, (long) archive.length, job.id());
        audit.record(tenant, "system", null, "data export worker", "system", null, "data_export.completed", "data_export_job", job.id(), null,
                Map.of("bytes", archive.length, "files", zip.entries().size()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportId", job.id());
        data.put("linkId", link);
        data.put("bytes", (long) archive.length);
        events.publish(tenant, DomainEventType.ORGANIZATION_EXPORT_READY, job.id(), data);
        Map<String, Object> contact = jdbc.queryForMap("SELECT email::text AS email, mobile_phone::text AS mobile_phone FROM staff WHERE id = ?", job.requestedBy());
        String url = downloadUrl(token);
        String mobile = (String) contact.get("mobile_phone");
        if (mobile != null && !mobile.isBlank() && notifications.sendable(NotificationPurpose.EXPORT_READY, "en")) {
            notifications.request(new NotificationRequest(NotificationPurpose.EXPORT_READY, "data_export_job", job.id(), null, mobile, "en", List.of(url),
                    null, null, null, "export-ready:" + job.id()));
        }
        return new Ready(token, (String) contact.get("email"), mobile);
    }

    String downloadUrl(String token) {
        return webBase + "/export-download/" + token;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void fail(UUID jobId) {
        jdbc.update("UPDATE data_export_jobs SET status = 'failed', error_message = 'The export could not be completed. Start it again.' WHERE id = ?", jobId);
        audit.record(tenantContext.current(), "system", null, "data export worker", "system", null, "data_export.failed", "data_export_job", jobId, null, Map.of());
    }

    // ---------------------------------------------------------------- the link (public, OTP to the owner's mobile)

    @Transactional(propagation = Propagation.MANDATORY)
    public LinkView view(String hash) {
        Map<String, Object> l = live(hash, false);
        return new LinkView(jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantContext.current()),
                ((Timestamp) l.get("expires_at")).toInstant(), (Integer) l.get("max_downloads") - (Integer) l.get("downloads"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Otp issueOtp(String hash) {
        Map<String, Object> l = live(hash, true);
        Instant now = clock.now();
        Timestamp last = (Timestamp) l.get("otp_expires_at");
        if (last != null && last.toInstant().minus(OTP_TTL).plus(OTP_GAP).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp-too-soon", "Please wait", "A code was sent a moment ago.");
        }
        String mobile = jdbc.queryForObject("SELECT mobile_phone::text FROM staff WHERE id = ?", String.class, l.get("recipient_staff_id"));
        if (mobile == null || mobile.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "no-mobile", "No mobile number", "The owner has no mobile number on file.");
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        UUID challenge = UUID.randomUUID();
        jdbc.update("UPDATE data_export_links SET otp_hash = ?, otp_expires_at = ?, otp_attempts = 0, otp_challenge_id = ? WHERE token_hash = ?",
                OpaqueTokens.sha256Hex(challenge + ":" + code), Timestamp.from(now.plus(OTP_TTL)), challenge, hash);
        return new Otp(mobile, code);
    }

    /** One download per OTP. A wrong code counts an attempt and is committed (outcome, not exception). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Download download(String hash, String otp, String ip) {
        UUID tenant = tenantContext.current();
        Map<String, Object> l = jdbc.queryForList("SELECT l.*, j.object_key FROM data_export_links l JOIN data_export_jobs j ON j.id = l.job_id WHERE l.token_hash = ? FOR UPDATE OF l", hash)
                .stream().findFirst().orElse(null);
        Instant now = clock.now();
        if (l == null || !((Timestamp) l.get("expires_at")).toInstant().isAfter(now) || (Integer) l.get("downloads") >= (Integer) l.get("max_downloads")) {
            return new Download(Outcome.EXPIRED, null);
        }
        UUID challenge = (UUID) l.get("otp_challenge_id");
        Timestamp otpExp = (Timestamp) l.get("otp_expires_at");
        boolean live = challenge != null && otpExp != null && otpExp.toInstant().isAfter(now) && (Integer) l.get("otp_attempts") < OTP_MAX_ATTEMPTS;
        if (!live || otp == null || !MessageDigest.isEqual(OpaqueTokens.sha256Hex(challenge + ":" + otp).getBytes(StandardCharsets.UTF_8),
                String.valueOf(l.get("otp_hash")).getBytes(StandardCharsets.UTF_8))) {
            if (live) {
                jdbc.update("UPDATE data_export_links SET otp_attempts = otp_attempts + 1 WHERE token_hash = ?", hash);
            }
            return new Download(Outcome.OTP_INVALID, null);
        }
        jdbc.update("UPDATE data_export_links SET downloads = downloads + 1, otp_hash = NULL, otp_challenge_id = NULL WHERE token_hash = ?", hash);
        UUID job = (UUID) l.get("job_id");
        byte[] enc = storage.get(ObjectStorage.Bucket.CLEAN, (String) l.get("object_key")).orElseThrow(ApiException::notFound);
        UUID staff = (UUID) l.get("recipient_staff_id");
        ActorDirectory.Actor a = actors.findActor(tenant, staff).orElse(null);
        audit.record(tenant, "staff", staff, a == null ? "owner" : a.name(), a == null ? null : a.role(), ip, "data_export.downloaded", "data_export_job", job, null,
                Map.of("download", (Integer) l.get("downloads") + 1, "otpChallengeId", challenge.toString()));
        return new Download(Outcome.OK, cipher.decrypt(enc, aad(tenant, job)));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> live(String hash, boolean lock) {
        Map<String, Object> l = jdbc.queryForList("SELECT * FROM data_export_links WHERE token_hash = ?" + (lock ? " FOR UPDATE" : ""), hash).stream().findFirst().orElse(null);
        if (l == null || !((Timestamp) l.get("expires_at")).toInstant().isAfter(clock.now()) || (Integer) l.get("downloads") >= (Integer) l.get("max_downloads")) {
            throw gone();
        }
        return l;
    }

    /** Every column except binary ones (ciphertext is useless outside Nabd). The table name comes from DATASETS only. */
    private ExportArchive.Table read(UUID tenant, String table) {
        List<String> cols = new ArrayList<>();
        List<Integer> keep = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        jdbc.query("SELECT * FROM " + table + " WHERE tenant_id = ? ORDER BY 1", rs -> {
            if (cols.isEmpty() && keep.isEmpty()) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    int type = md.getColumnType(i);
                    if (type != Types.BINARY && type != Types.VARBINARY && type != Types.LONGVARBINARY && type != Types.BLOB) {
                        cols.add(md.getColumnName(i));
                        keep.add(i);
                    }
                }
            }
            List<Object> r = new ArrayList<>(keep.size());
            for (int i : keep) {
                Object v = rs.getObject(i);
                r.add(v instanceof java.sql.Array a ? String.join("|", java.util.Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList()) : v);
            }
            rows.add(r);
        }, tenant);
        if (cols.isEmpty()) { // no rows: the header still lists the columns
            cols.addAll(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND data_type <> 'bytea' ORDER BY ordinal_position", String.class, table));
        }
        return new ExportArchive.Table(table, cols, rows);
    }

    private static boolean fhirCovered(String table) {
        return Set.of("patients", "queue_entries", "prescriptions", "prescription_items", "vitals").contains(table);
    }

    /** FHIR R4 resources as NDJSON: Patient, Encounter (visits), MedicationRequest, Observation (vital signs, LOINC codes). */
    private void fhir(UUID tenant, String dataset, ExportArchive zip) {
        List<Map<String, Object>> out = new ArrayList<>();
        String file;
        switch (dataset) {
            case "patients" -> {
                file = "fhir/Patient.ndjson";
                for (Map<String, Object> p : jdbc.queryForList("SELECT id, mrn, name, phone, dob, gender FROM patients WHERE tenant_id = ? ORDER BY id", tenant)) {
                    Map<String, Object> r = res("Patient", p.get("id"));
                    if (p.get("mrn") != null) r.put("identifier", List.of(Map.of("system", "urn:nabd:mrn", "value", p.get("mrn"))));
                    r.put("name", List.of(Map.of("text", p.get("name"))));
                    r.put("gender", gender((String) p.get("gender")));
                    if (p.get("dob") != null) r.put("birthDate", p.get("dob").toString());
                    if (p.get("phone") != null) r.put("telecom", List.of(Map.of("system", "phone", "value", p.get("phone"))));
                    out.add(r);
                }
            }
            case "visits" -> {
                file = "fhir/Encounter.ndjson";
                for (Map<String, Object> e : jdbc.queryForList("SELECT id, patient_id, status, created_at FROM queue_entries WHERE tenant_id = ? ORDER BY id", tenant)) {
                    Map<String, Object> r = res("Encounter", e.get("id"));
                    r.put("status", "completed".equals(e.get("status")) ? "finished" : "cancelled".equals(e.get("status")) ? "cancelled" : "in-progress");
                    r.put("class", Map.of("system", "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code", "AMB"));
                    r.put("subject", Map.of("reference", "Patient/" + e.get("patient_id")));
                    r.put("period", Map.of("start", ((Timestamp) e.get("created_at")).toInstant().toString()));
                    out.add(r);
                }
            }
            case "prescriptions" -> {
                file = "fhir/MedicationRequest.ndjson";
                for (Map<String, Object> m : jdbc.queryForList("SELECT i.id, i.drug_name, i.dosage, i.frequency, i.duration, i.instructions, p.patient_id, p.queue_entry_id, p.status, p.signed_at "
                        + "FROM prescription_items i JOIN prescriptions p ON p.id = i.prescription_id WHERE i.tenant_id = ? ORDER BY i.id", tenant)) {
                    Map<String, Object> r = res("MedicationRequest", m.get("id"));
                    r.put("status", "signed".equals(m.get("status")) ? "active" : "draft");
                    r.put("intent", "order");
                    r.put("medicationCodeableConcept", Map.of("text", m.get("drug_name")));
                    r.put("subject", Map.of("reference", "Patient/" + m.get("patient_id")));
                    r.put("encounter", Map.of("reference", "Encounter/" + m.get("queue_entry_id")));
                    String dose = String.join(" ", java.util.stream.Stream.of(m.get("dosage"), m.get("frequency"), m.get("duration"), m.get("instructions"))
                            .filter(java.util.Objects::nonNull).map(String::valueOf).toList());
                    if (!dose.isBlank()) r.put("dosageInstruction", List.of(Map.of("text", dose)));
                    if (m.get("signed_at") != null) r.put("authoredOn", ((Timestamp) m.get("signed_at")).toInstant().toString());
                    out.add(r);
                }
            }
            case "results" -> {
                file = "fhir/Observation.ndjson";
                // LOINC vital-sign codes and UCUM units, as in the FHIR R4 vital signs profile
                String[][] map = {{"height_cm", "8302-2", "Body height", "cm"}, {"weight_kg", "29463-7", "Body weight", "kg"},
                        {"bp_systolic", "8480-6", "Systolic blood pressure", "mm[Hg]"}, {"bp_diastolic", "8462-4", "Diastolic blood pressure", "mm[Hg]"},
                        {"pulse_bpm", "8867-4", "Heart rate", "/min"}, {"temp_celsius", "8310-5", "Body temperature", "Cel"},
                        {"spo2_percent", "59408-5", "Oxygen saturation in Arterial blood by Pulse oximetry", "%"}};
                for (Map<String, Object> v : jdbc.queryForList("SELECT * FROM vitals WHERE tenant_id = ? ORDER BY id", tenant)) {
                    for (String[] f : map) {
                        if (v.get(f[0]) == null) continue;
                        Map<String, Object> r = res("Observation", v.get("id") + "-" + f[1]);
                        r.put("status", "final");
                        r.put("category", List.of(Map.of("coding", List.of(Map.of("system", "http://terminology.hl7.org/CodeSystem/observation-category", "code", "vital-signs")))));
                        r.put("code", Map.of("coding", List.of(Map.of("system", "http://loinc.org", "code", f[1], "display", f[2]))));
                        r.put("subject", Map.of("reference", "Patient/" + v.get("patient_id")));
                        r.put("encounter", Map.of("reference", "Encounter/" + v.get("queue_entry_id")));
                        r.put("effectiveDateTime", ((Timestamp) v.get("recorded_at")).toInstant().toString());
                        r.put("valueQuantity", Map.of("value", v.get(f[0]), "unit", f[3], "system", "http://unitsofmeasure.org", "code", f[3]));
                        out.add(r);
                    }
                }
            }
            default -> {
                return;
            }
        }
        StringBuilder sb = new StringBuilder();
        out.forEach(r -> sb.append(json.writeValueAsString(r)).append('\n'));
        zip.add(file, sb.toString().getBytes(StandardCharsets.UTF_8), out.size());
    }

    private static Map<String, Object> res(String type, Object id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", type);
        r.put("id", String.valueOf(id));
        return r;
    }

    private static String gender(String g) {
        return g == null ? "unknown" : switch (g.toLowerCase()) {
            case "male", "m" -> "male";
            case "female", "f" -> "female";
            case "other", "o" -> "other";
            default -> "unknown";
        };
    }

    /** One PDF per patient: demographics, visits, prescriptions, vital signs. */
    private void patientPdfs(UUID tenant, ExportArchive zip) {
        for (Map<String, Object> p : jdbc.queryForList("SELECT id, mrn, name, dob, gender FROM patients WHERE tenant_id = ? ORDER BY id", tenant)) {
            UUID id = (UUID) p.get("id");
            List<String> lines = new ArrayList<>(List.of("# " + p.get("name"), "MRN: " + p.get("mrn"), "Date of birth: " + p.get("dob"), "Sex: " + p.get("gender"), "", "## Visits"));
            jdbc.queryForList("SELECT queue_date, status FROM queue_entries WHERE tenant_id = ? AND patient_id = ? ORDER BY queue_date", tenant, id)
                    .forEach(v -> lines.add(v.get("queue_date") + "  " + v.get("status")));
            lines.add("");
            lines.add("## Prescriptions");
            jdbc.queryForList("SELECT p.signed_at, i.drug_name, i.dosage, i.frequency, i.duration FROM prescription_items i JOIN prescriptions p ON p.id = i.prescription_id "
                    + "WHERE p.tenant_id = ? AND p.patient_id = ? ORDER BY p.created_at, i.display_order", tenant, id)
                    .forEach(m -> lines.add(m.get("signed_at") + "  " + m.get("drug_name") + " " + java.util.Objects.toString(m.get("dosage"), "") + " "
                            + java.util.Objects.toString(m.get("frequency"), "") + " " + java.util.Objects.toString(m.get("duration"), "")));
            lines.add("");
            lines.add("## Vital signs");
            jdbc.queryForList("SELECT recorded_at, height_cm, weight_kg, bp_systolic, bp_diastolic, pulse_bpm, temp_celsius, spo2_percent FROM vitals WHERE tenant_id = ? AND patient_id = ? ORDER BY recorded_at", tenant, id)
                    .forEach(v -> lines.add(v.get("recorded_at") + "  height " + v.get("height_cm") + " cm, weight " + v.get("weight_kg") + " kg, BP " + v.get("bp_systolic") + "/"
                            + v.get("bp_diastolic") + ", pulse " + v.get("pulse_bpm") + ", temp " + v.get("temp_celsius") + " C, SpO2 " + v.get("spo2_percent") + "%"));
            zip.add("patients/" + id + ".pdf", SimplePdf.render(lines), 1);
        }
    }

    /** The clean copies of available files (the metadata is in files/file_objects.*). */
    private void files(UUID tenant, ExportArchive zip) {
        for (Map<String, Object> f : jdbc.queryForList("SELECT id, storage_key FROM file_objects WHERE tenant_id = ? AND status = 'available' ORDER BY id", tenant)) {
            storage.get(ObjectStorage.Bucket.CLEAN, (String) f.get("storage_key")).ifPresent(b -> zip.add("files/blobs/" + f.get("id"), b, 1));
        }
    }

    private void progress(UUID job, String dataset, long rows, String status) {
        jdbc.update("UPDATE data_export_jobs SET progress = jsonb_set(coalesce(progress, '{}'::jsonb), ARRAY[?], ?::jsonb) WHERE id = ?",
                dataset, json.writeValueAsString(Map.of("rows", rows, "status", status)), job);
    }

    private static FieldCipher.Aad aad(UUID tenant, UUID job) {
        return FieldCipher.Aad.of(tenant, "data_export_jobs", "archive", job);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }

    private static ApiException running() {
        return new ApiException(HttpStatus.CONFLICT, "export-running", "Export already running", "An export is already running for this clinic. Wait for it to finish.",
                Map.of("code", "EXPORT_RUNNING"));
    }

    static ApiException gone() {
        return new ApiException(HttpStatus.GONE, "export-link-expired", "Link expired", "This download link has expired or was used three times. Start a new export.",
                Map.of("code", "export-link-expired"));
    }

}
