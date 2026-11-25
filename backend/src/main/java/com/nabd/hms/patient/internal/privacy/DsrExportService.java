package com.nabd.hms.patient.internal.privacy;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.dpa.SimplePdf;
import com.nabd.hms.patient.securelink.SecureLinkService;
import com.nabd.hms.shared.FileStore;
import com.nabd.hms.shared.PatientDataExporter;
import com.nabd.hms.shared.PatientDataExporter.Table;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PLT-047: builds a patient's data package for an access request and hands it over. The content is exactly what the module export
 * registry ({@link PatientDataExporter}) declares, read under the clinic's own tenant context (RLS on, tenant_id in every query):
 * data.json (every exported row, plain JSON until the IDC-055 FHIR mapper is merged), summary.pdf (readable), the patient's
 * available files, and manifest.json (row count per table, what was left out and why, SHA-256 per file). The zip is encrypted with
 * the clinic's data key ({@link DsrGate}) and delivered as an IDC-015 secure link: 72 hours, an OTP to the requester's own number
 * for every open, and a WhatsApp body that carries a first name and the link only.
 * shortcut: the package is built in memory and files are included up to MAX_FILE_BYTES in total (the rest are listed in the
 * manifest as not included); move to a streamed build and download before patients with large imaging files need the full set.
 */
@Service
public class DsrExportService {

    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Map<String, String> EXT = Map.of("application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png", "text/csv", "csv", "image/svg+xml", "svg");

    record Claimed(UUID id, UUID patientId, UUID requesterId, int attempt) {
    }

    /** A delivery that cannot happen yet (no approved template, no messaging consent, no phone): retried later, shown in the queue. */
    static final class NotDeliverable extends RuntimeException {
        NotDeliverable(String reason) {
            super(reason);
        }
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;
    private final NotificationPort notifications;
    private final FileStore files;
    private final SecureLinkService links;
    private final DsrGate gate;
    private final DsrService requests;
    private final ObjectMapper json;
    private final List<PatientDataExporter> exporters;

    DsrExportService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, AuditService audit, ActorDirectory actors, EventPublisher events,
                     NotificationPort notifications, FileStore files, SecureLinkService links, DsrGate gate, DsrService requests, ObjectMapper json,
                     List<PatientDataExporter> exporters) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
        this.notifications = notifications;
        this.files = files;
        this.links = links;
        this.gate = gate;
        this.requests = requests;
        this.json = json;
        this.exporters = exporters.stream().sorted(Comparator.comparing(PatientDataExporter::module)).toList();
    }

    /** The module export registry, ordered by module. */
    public List<PatientDataExporter> registry() {
        return exporters;
    }

    // ---------------------------------------------------------------- worker steps (each its own tenant transaction)

    /**
     * Claims the oldest access request waiting for its package. A claim that did not finish (failure, crashed instance) comes
     * back by itself: 15 minutes after the first attempt, doubling up to 16 hours, so nothing is ever marked done before it is.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Claimed claimNext() {
        return jdbc.query("UPDATE dsr_requests SET export_started_at = now(), export_attempts = export_attempts + 1, status = 'in_progress' WHERE id = ("
                        + "SELECT id FROM dsr_requests WHERE tenant_id = ? AND type = 'access' AND status IN ('received', 'in_progress') AND export_file_id IS NULL "
                        + "AND (export_started_at IS NULL OR export_started_at < now() - interval '15 minutes' * power(2, least(export_attempts - 1, 6))) "
                        + "ORDER BY received_at LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING id, patient_id, requester_patient_id, export_attempts",
                (rs, i) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4)),
                tenantContext.current()).stream().findFirst().orElse(null);
    }

    /** Builds, stores and delivers the package; the request is completed in the same transaction or not at all. */
    @Transactional(propagation = Propagation.MANDATORY)
    void build(Claimed c) {
        UUID tenant = tenantContext.current();
        if (!gate.mayRequestFor(c.requesterId(), c.patientId())) { // asked again: the guardian link may have ended since the request
            jdbc.update("UPDATE dsr_requests SET status = 'rejected', completed_at = now(), closure_reason = 'requester_not_authorised' WHERE id = ?", c.id());
            requests.step(tenant, c.id(), "system", null, "rejected", "requester no longer authorised");
            audit.record(tenant, "system", null, "privacy request export", "system", null, "dsr.rejected", "dsr_request", c.id(), null,
                    Map.of("reason", "requester_not_authorised"));
            return;
        }
        requests.step(tenant, c.id(), "system", null, "export_started", "attempt " + c.attempt());
        Instant now = clock.now();
        Map<String, Map<String, List<Map<String, Object>>>> data = read(c.patientId());
        List<Map<String, Object>> access = accessLog(tenant, data);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<String> notIncluded = new ArrayList<>();
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", "nabd-patient-data-export");
        bundle.put("formatVersion", 1);
        bundle.put("standard", "plain JSON (a FHIR R4 bundle replaces this file once the IDC-055 mapping is available)");
        bundle.put("generatedAt", now.toString());
        bundle.put("requestId", c.id().toString());
        bundle.put("clinic", Map.of("id", tenant.toString(), "name", clinicName(tenant)));
        bundle.put("patientId", c.patientId().toString());
        bundle.put("modules", data);
        bundle.put("accessLog", access);
        entries.put("data.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle));
        entries.put("summary.pdf", SimplePdf.render(summary(tenant, c, now, data, access)));
        addFiles(data, entries, notIncluded);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("requestId", c.id().toString());
        manifest.put("generatedAt", now.toString());
        manifest.put("tables", tablesManifest(data));
        manifest.put("files", entries.entrySet().stream().map(e -> Map.of("name", e.getKey(), "bytes", e.getValue().length, "sha256", sha256(e.getValue()))).toList());
        manifest.put("filesNotIncluded", notIncluded);
        manifest.put("notes", List.of("Only signed clinical notes, signed prescriptions and issued documents are included.",
                "Internal columns (keys, hashes, storage locations) and encrypted identifiers are left out.",
                "The access list covers the period the clinic keeps its read log online."));
        entries.put("manifest.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        byte[] zip = zip(entries);

        SecureLinkService.ExportLink link = links.issueDataExport(c.id(), c.patientId(), c.requesterId())
                .orElseThrow(() -> new NotDeliverable("the requester has no usable mobile number or the record cannot be processed"));
        String language = notifications.sendable(NotificationPurpose.SECURE_LINK, language(c.requesterId())) ? language(c.requesterId()) : "en";
        if (!notifications.sendable(NotificationPurpose.SECURE_LINK, language)) {
            throw new NotDeliverable("the clinic has no approved secure_link message template");
        }
        // the body carries the first name and the link only: nothing says what the link holds
        NotificationOutcome sent = notifications.request(new NotificationRequest(NotificationPurpose.SECURE_LINK, "dsr_request", c.id(), c.requesterId(), null,
                language, List.of(link.firstName(), link.url()), null, null, link.expiresAt(), "dsr-export:" + c.id() + ":" + link.linkId()));
        if (!"requested".equalsIgnoreCase(sent.status())) {
            throw new NotDeliverable("the requester has not agreed to receive messages");
        }
        UUID fileId = UUID.randomUUID();
        gate.store(c.id(), fileId, zip);
        jdbc.update("UPDATE dsr_requests SET status = 'completed', completed_at = ?, export_file_id = ?, export_link_id = ? WHERE id = ?",
                Timestamp.from(clock.now()), fileId, link.linkId(), c.id());
        requests.step(tenant, c.id(), "system", null, "export_ready", "link valid until " + link.expiresAt());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fileId", fileId.toString());
        after.put("linkId", link.linkId().toString());
        after.put("bytes", zip.length);
        after.put("sha256", sha256(zip));
        audit.record(tenant, "system", null, "privacy request export", "system", null, "dsr.export_ready", "dsr_request", c.id(), null, after);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("requestId", c.id());
        ev.put("fileId", fileId);
        events.publish(tenant, DomainEventType.PATIENT_DSR_EXPORT_READY, c.id(), ev);
    }

    /** After a failed build (its transaction rolled back): one event and audit row; the claim's back-off brings it back. */
    @Transactional(propagation = Propagation.MANDATORY)
    void failed(Claimed c, String reason) {
        UUID tenant = tenantContext.current();
        requests.step(tenant, c.id(), "system", null, "export_failed", reason);
        audit.record(tenant, "system", null, "privacy request export", "system", null, "dsr.export_failed", "dsr_request", c.id(), null,
                Map.of("attempt", c.attempt(), "reason", reason));
    }

    // ---------------------------------------------------------------- reading the registry

    /** module -> table -> rows of this patient in the bound clinic, for every exported table of the registry (empty tables included). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Map<String, List<Map<String, Object>>>> read(UUID patientId) {
        UUID tenant = tenantContext.current();
        Map<String, Map<String, List<Map<String, Object>>>> out = new LinkedHashMap<>();
        for (PatientDataExporter e : exporters) {
            Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
            for (Table t : e.tables()) {
                if (t.exported()) {
                    tables.put(t.name(), rows(tenant, patientId, t));
                }
            }
            out.put(e.module(), tables);
        }
        return out;
    }

    private List<Map<String, Object>> rows(UUID tenant, UUID patient, Table t) {
        ident(t.name());
        t.patientColumns().forEach(DsrExportService::ident);
        String mine = "(" + String.join(" OR ", t.patientColumns().stream().map(c -> "t." + c + " = ?").toList()) + ")"
                + (t.filter() == null ? "" : " AND (" + t.filter() + ")");
        List<Object> args = new ArrayList<>(List.of(tenant));
        String sql;
        if (t.parent() == null) {
            sql = "SELECT t.* FROM " + t.name() + " t WHERE t.tenant_id = ? AND " + mine + " ORDER BY 1";
        } else {
            sql = "SELECT c.* FROM " + t.name() + " c WHERE c.tenant_id = ? AND c." + ident(t.parentKey()) + " IN (SELECT t.id FROM " + ident(t.parent())
                    + " t WHERE t.tenant_id = ? AND " + mine + ") ORDER BY 1";
            args.add(tenant);
        }
        t.patientColumns().forEach(c -> args.add(patient));
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                int type = md.getColumnType(i);
                String col = md.getColumnName(i);
                if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB || t.omit().contains(col)) {
                    continue;
                }
                row.put(col, plain(rs.getObject(i), md.getColumnTypeName(i)));
            }
            rows.add(row);
        }, args.toArray());
        return rows;
    }

    private static String ident(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalStateException("export registry: not an identifier");
        }
        return name;
    }

    private Object plain(Object v, String typeName) {
        if (v == null || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof java.sql.Array a) {
            try {
                List<String> l = new ArrayList<>();
                for (Object o : (Object[]) a.getArray()) {
                    l.add(o == null ? null : String.valueOf(o));
                }
                return l;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        String s = String.valueOf(v);
        if ("json".equals(typeName) || "jsonb".equals(typeName)) {
            return json.readTree(s);
        }
        return s;
    }

    // ---------------------------------------------------------------- package parts

    /** Who opened the record: every read-log row with the reader's name and role (staff of this clinic; others by their kind). */
    private List<Map<String, Object>> accessLog(UUID tenant, Map<String, Map<String, List<Map<String, Object>>>> data) {
        List<Map<String, Object>> reads = data.getOrDefault("shared", Map.of()).getOrDefault("phi_read_log", List.of());
        Set<UUID> ids = new LinkedHashSet<>();
        reads.forEach(r -> {
            if (r.get("actor_id") != null) {
                ids.add(UUID.fromString(String.valueOf(r.get("actor_id"))));
            }
        });
        Map<UUID, ActorDirectory.Actor> who = ids.isEmpty() ? Map.of() : actors.findActors(tenant, ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : reads) {
            ActorDirectory.Actor a = r.get("actor_id") == null ? null : who.get(UUID.fromString(String.valueOf(r.get("actor_id"))));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at", r.get("read_at"));
            e.put("by", a == null ? String.valueOf(r.get("actor_type")) : a.name());
            e.put("role", a == null ? String.valueOf(r.get("actor_type")) : a.role());
            e.put("what", r.get("resource_type"));
            e.put("purpose", r.get("purpose_code"));
            e.put("emergencyAccess", r.get("break_glass_grant_id") != null);
            out.add(e);
        }
        return out;
    }

    private List<String> summary(UUID tenant, Claimed c, Instant now, Map<String, Map<String, List<Map<String, Object>>>> data, List<Map<String, Object>> access) {
        List<String> l = new ArrayList<>();
        l.add("# Your data from " + clinicName(tenant));
        l.add("Prepared on " + clock.clinicDate(tenant, now) + " for request " + c.id().toString().substring(0, 8) + ".");
        l.add("This summary lists what the clinic holds about you. The file data.json in the same package holds every record in full.");
        l.add("Only signed clinical notes, signed prescriptions and issued documents are included.");
        l.add("");
        l.add("## You");
        Map<String, Object> p = data.getOrDefault("patient", Map.of()).getOrDefault("patients", List.of()).stream().findFirst().orElse(Map.of());
        for (String[] f : new String[][]{{"name", "Name"}, {"mrn", "Record number"}, {"dob", "Date of birth"}, {"gender", "Sex"}, {"phone", "Phone"}, {"email", "Email"}, {"address", "Address"}}) {
            if (p.get(f[0]) != null) {
                l.add(f[1] + ": " + p.get(f[0]));
            }
        }
        l.add("");
        l.add("## What is in this package");
        data.forEach((module, tables) -> tables.forEach((table, rows) -> {
            if (!rows.isEmpty()) {
                l.add(label(table) + ": " + rows.size() + (rows.size() == 1 ? " record" : " records"));
            }
        }));
        l.add("");
        l.add("## Who opened your record");
        if (access.isEmpty()) {
            l.add("No one opened your record in the period the clinic keeps this list for.");
        }
        access.forEach(a -> l.add(a.get("at") + "  " + a.get("by") + " (" + a.get("role") + ")  " + a.get("what")
                + (Boolean.TRUE.equals(a.get("emergencyAccess")) ? "  emergency access" : "")));
        data.forEach((module, tables) -> tables.forEach((table, rows) -> {
            if (rows.isEmpty() || "phi_read_log".equals(table) || "patients".equals(table)) {
                return;
            }
            l.add("");
            l.add("## " + label(table));
            for (Map<String, Object> r : rows) {
                List<String> parts = new ArrayList<>();
                r.forEach((k, v) -> {
                    if (v != null && !k.equals("id") && !k.endsWith("_id") && !k.endsWith("_seq") && !k.endsWith("_by") && !k.equals("version")
                            && !(v instanceof Collection<?> col && col.isEmpty()) && !String.valueOf(v).isBlank()) {
                        parts.add(k.replace('_', ' ') + ": " + v);
                    }
                });
                l.add("- " + String.join("; ", parts));
            }
        }));
        return l;
    }

    private static String label(String table) {
        String s = table.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private List<Map<String, Object>> tablesManifest(Map<String, Map<String, List<Map<String, Object>>>> data) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PatientDataExporter e : exporters) {
            for (Table t : e.tables()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("module", e.module());
                m.put("table", t.name());
                if (t.exported()) {
                    m.put("rows", data.get(e.module()).get(t.name()).size());
                } else {
                    m.put("notExported", t.excludedReason());
                }
                out.add(m);
            }
        }
        return out;
    }

    /** The patient's available files (the generic file pipeline's own view decides what is a shareable file), up to the cap. */
    private void addFiles(Map<String, Map<String, List<Map<String, Object>>>> data, Map<String, byte[]> entries, List<String> notIncluded) {
        long total = 0;
        for (Map<String, Object> f : data.getOrDefault("shared", Map.of()).getOrDefault("file_objects", List.of())) {
            UUID id = UUID.fromString(String.valueOf(f.get("id")));
            FileStore.FileInfo info = files.info(id).filter(i -> "available".equals(i.status())).orElse(null);
            if (info == null) {
                continue;
            }
            byte[] bytes = total + info.sizeBytes() > MAX_FILE_BYTES ? null : files.content(id).orElse(null);
            if (bytes == null) {
                notIncluded.add(id.toString());
                continue;
            }
            total += bytes.length;
            entries.put("files/" + id + "." + EXT.getOrDefault(info.contentType(), "bin"), bytes);
        }
    }

    private String clinicName(UUID tenant) {
        return jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenant);
    }

    private String language(UUID patient) {
        return jdbc.query("SELECT language FROM patient_notification_preferences WHERE tenant_id = ? AND patient_id = ? AND language IS NOT NULL",
                (rs, i) -> rs.getString(1), tenantContext.current(), patient).stream().findFirst().orElse("en");
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String describe(RuntimeException e) {
        return e instanceof NotDeliverable n ? n.getMessage() : "the package could not be built (" + e.getClass().getSimpleName() + ")";
    }
}
