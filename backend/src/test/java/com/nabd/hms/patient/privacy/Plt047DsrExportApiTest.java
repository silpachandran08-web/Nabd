package com.nabd.hms.patient.privacy;

import com.nabd.hms.patient.internal.privacy.DsrExportJob;
import com.nabd.hms.shared.files.ObjectStorage;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-047 steps 2 and 3: the package (every registry table, only signed notes and prescriptions, only this clinic, the access
 * list), its storage (encrypted), and its delivery through the IDC-015 secure link (first name and link in the WhatsApp body, a
 * code to the requester's number for every open, 72 hours, three opens, asked again at open time).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Plt047DsrExportApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(4_750_000);
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Autowired DsrExportJob job;
    @Autowired ObjectStorage storage;

    private record Clinic(SeededTenant t, SeededStaff staff, String token) {
        UUID id() {
            return t.id();
        }
    }

    private static String phone() {
        return "+9190" + String.format("%08d", SEQ.incrementAndGet());
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private Clinic clinic(boolean withTemplate) {
        SeededTenant t = seedTenant();
        String u = UUID.randomUUID().toString().substring(0, 8);
        SeededStaff staff = seedStaff(t, seedRole(t.id(), "Owner", true, fullGrant("staff"), fullGrant("patients"), fullGrant("compliance"), fullGrant("setup")),
                "dsrx-" + u + "@a.com", phone(), false);
        if (withTemplate) {
            template(t.id());
        }
        return new Clinic(t, staff, loginAndGetAccessToken(staff));
    }

    private void template(UUID t) {
        inTenantTx(t, () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'secure_link', ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, a secure message is ready: {{2}}', 2, 'approved')""", t, "m_" + UUID.randomUUID()));
    }

    private UUID patient(UUID t, String name, String phone, String dob, UUID guardian, boolean messagingConsent) {
        UUID id = inTenantTx(t, () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?,?,?,?::date,'other',?) RETURNING id",
                UUID.class, t, name, phone, dob, guardian));
        if (messagingConsent) {
            inTenantTx(t, () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", t, id));
        }
        return id;
    }

    /** One visit with a note, a prescription with one line, vital signs and an invoice; the note and the prescription signed or left as drafts. */
    private void visit(Clinic c, UUID p, int token, boolean signed, String marker) {
        inTenantTx(c.id(), () -> {
            UUID entry = jdbc.queryForObject("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number) "
                    + "VALUES (?,?,?,(SELECT id FROM departments WHERE is_default),current_date,?) RETURNING id", UUID.class, c.id(), p, c.staff().id(), token);
            jdbc.update("INSERT INTO clinical_notes (tenant_id, queue_entry_id, patient_id, doctor_id, subjective, status, signed_at, signed_by) VALUES (?,?,?,?,?,?,?,?)",
                    c.id(), entry, p, c.staff().id(), "NOTE-" + marker, signed ? "signed" : "draft", signed ? java.sql.Timestamp.from(Instant.now()) : null, signed ? c.staff().id() : null);
            UUID rx = jdbc.queryForObject("INSERT INTO prescriptions (tenant_id, queue_entry_id, patient_id, doctor_id) VALUES (?,?,?,?) RETURNING id", UUID.class, c.id(), entry, p, c.staff().id());
            jdbc.update("INSERT INTO prescription_items (tenant_id, prescription_id, drug_name, dosage, frequency) VALUES (?,?,?, '1 tab', 'daily')", c.id(), rx, "RX-" + marker);
            if (signed) {
                jdbc.update("UPDATE prescriptions SET status = 'signed', signed_at = now(), signed_by = ? WHERE id = ?", c.staff().id(), rx);
            }
            jdbc.update("INSERT INTO vitals (tenant_id, queue_entry_id, patient_id, weight_kg, recorded_by) VALUES (?,?,?,61.5,?)", c.id(), entry, p, c.staff().id());
            jdbc.update("INSERT INTO invoices (tenant_id, invoice_number, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, created_by) VALUES (?,?,?,?,?,500,0,500,?)",
                    c.id(), "INV-" + marker, entry, p, c.staff().id(), c.staff().id());
            return null;
        });
    }

    private String request(Clinic c, Map<String, Object> body) {
        ResponseEntity<Map> r = exchange("/v1/privacy/requests", HttpMethod.POST, authedJsonBodyWithHeader(c.token(), "Idempotency-Key", UUID.randomUUID().toString(), body), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("requestId");
    }

    private Map<String, Object> row(String requestId) {
        return su.queryForMap("SELECT * FROM dsr_requests WHERE id = ?::uuid", requestId);
    }

    /** One worker pass for the clinic (the scheduled pass is switched off in ApiTestBase, so the order of steps is the test's). */
    private void runWorker(Clinic c) {
        job.runFor(c.id());
    }

    private record Link(UUID id, String token, List<String> params) {
    }

    /** The link as the patient gets it: from the WhatsApp request's parameters (the token is stored nowhere). */
    private Link link(String requestId) {
        UUID linkId = (UUID) row(requestId).get("export_link_id");
        UUID note = su.queryForObject("SELECT id FROM notification_requests WHERE subject_type = 'dsr_request' AND subject_id = ?::uuid", UUID.class, requestId);
        ResponseEntity<Map> claim = exchange("/internal/v1/notification-requests/" + note, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders() {{ set("X-Internal-Token", INTERNAL_COMMS_TOKEN); }}), Map.class);
        assertThat(claim.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claim.getBody()).containsEntry("purpose", "secure_link");
        List<String> params = (List<String>) claim.getBody().get("params");
        return new Link(linkId, params.get(1).substring(params.get(1).indexOf("/l/") + 3), params);
    }

    private static HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("User-Agent", "plt047-test/1.0");
        return h;
    }

    private ResponseEntity<Map> open(String token, String code) {
        return exchange("/v1/public/links/" + token + "/open", HttpMethod.POST, new HttpEntity<>(Map.of("otp", code), headers()), Map.class);
    }

    /** A code the test knows (the real one goes to the phone): stored exactly as SecureLinkService stores it. */
    private void setOtp(UUID linkId, String code) throws Exception {
        su.update("UPDATE secure_links SET otp_hash = ?, otp_expires_at = now() + interval '5 minutes' WHERE id = ?", sha(linkId + ":" + code), linkId);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                out.put(e.getName(), z.readAllBytes());
            }
        }
        return out;
    }

    private Map<String, byte[]> download(Link l, String code) throws Exception {
        setOtp(l.id(), code);
        ResponseEntity<Map> r = open(l.token(), code);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> file = (Map<String, Object>) r.getBody().get("dataExport");
        byte[] zip = Base64.getDecoder().decode((String) file.get("contentBase64"));
        assertThat(file).containsEntry("fileName", "my-data.zip").containsEntry("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip)));
        return unzip(zip);
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void thePackageHoldsThisPatientsSignedRecordInThisClinicOnlyAndArrivesThroughAnOtpLinkValidFor72Hours() throws Exception {
        Clinic c = clinic(true);
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null, true);
        visit(c, p, 1, true, "SIGNED-" + c.id());
        visit(c, p, 2, false, "DRAFT-" + c.id());
        // another patient of the same clinic, and the same person at another clinic: neither may appear
        UUID neighbour = patient(c.id(), "Bala Iyer", phone(), "1980-01-01", null, true);
        visit(c, neighbour, 3, true, "NEIGHBOUR-" + c.id());
        Clinic other = clinic(true);
        UUID elsewhere = patient(other.id(), "Asha Rao", phone(), "1990-01-01", null, true);
        visit(other, elsewhere, 1, true, "OTHERCLINIC-" + other.id());
        // someone opened the record: it must be in the access list with their name
        su.update("INSERT INTO phi_read_log (tenant_id, actor_type, actor_id, patient_id, resource_type, purpose_code) VALUES (?, 'staff', ?, ?, 'clinical_note', 'treatment')",
                c.id(), c.staff().id(), p);

        Instant asked = Instant.now();
        String id = request(c, Map.of("patientId", p.toString(), "type", "access"));
        runWorker(c);

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "completed");
        assertThat(Duration.between(asked, ((java.sql.Timestamp) row.get("completed_at")).toInstant())).as("built well inside the 10 minute target").isLessThan(Duration.ofMinutes(10));
        UUID fileId = (UUID) row.get("export_file_id");
        assertThat(su.queryForList("SELECT event FROM dsr_request_events WHERE request_id = ?::uuid ORDER BY id", String.class, id)).containsExactly("received", "export_started", "export_ready");
        assertThat(su.queryForList("SELECT action FROM audit_log WHERE entity_id = ?::uuid", String.class, id)).contains("dsr.received", "dsr.export_ready");
        Map<String, Object> ev = objectMapper.readValue(su.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'patient.dsr.export_ready.v1'", String.class, c.id()), Map.class);
        assertThat(ev).containsEntry("requestId", id).containsEntry("fileId", fileId.toString());
        assertThat(ev.keySet()).containsExactlyInAnyOrder("tenantId", "requestId", "fileId", "version");

        // stored encrypted: neither a zip nor readable text, and not a row of the generic file store
        byte[] stored = storage.get(ObjectStorage.Bucket.CLEAN, "tenant/" + c.id() + "/dsr/" + fileId + ".zip.enc").orElseThrow();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotStartWith("PK").doesNotContain("SIGNED-").doesNotContain("Asha");
        assertThat(su.queryForObject("SELECT count(*) FROM file_objects WHERE tenant_id = ? AND patient_id = ?", Integer.class, c.id(), p)).isZero();

        // the WhatsApp body: a first name and the link, nothing else; the link says nothing about what it holds
        Link l = link(id);
        assertThat(l.params()).hasSize(2);
        assertThat(l.params().get(0)).isEqualTo("Asha");
        assertThat(l.token()).hasSize(64);
        assertThat(l.params().get(1)).doesNotContainIgnoringCase("export").doesNotContainIgnoringCase("data").doesNotContain(p.toString()).doesNotContain(id);
        Map<String, Object> sl = su.queryForMap("SELECT * FROM secure_links WHERE id = ?", l.id());
        assertThat(sl).containsEntry("document_type", "data_export").containsEntry("patient_id", p).containsEntry("recipient_patient_id", p).containsEntry("max_opens", 3);
        assertThat(Duration.between(asked, ((java.sql.Timestamp) sl.get("expires_at")).toInstant())).isBetween(Duration.ofHours(72), Duration.ofHours(72).plusMinutes(5));

        // no code, a wrong code: nothing is handed over
        assertThat(open(l.token(), "000000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        setOtp(l.id(), "424242");
        ResponseEntity<Map> wrong = open(l.token(), "111111");
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody()).doesNotContainKey("dataExport");

        Map<String, byte[]> zip = download(l, "424242");
        assertThat(zip.keySet()).containsExactly("data.json", "summary.pdf", "manifest.json");
        assertThat(new String(zip.get("summary.pdf"), StandardCharsets.ISO_8859_1)).as("a readable PDF: the text is in the page streams").startsWith("%PDF-1.4")
                .contains("Name: Asha Rao").contains("WHO OPENED YOUR RECORD").contains("Test Staff \\(Owner\\)").contains("NOTE-SIGNED-").doesNotContain("NOTE-DRAFT-");
        String json = new String(zip.get("data.json"), StandardCharsets.UTF_8);
        assertThat(json).contains("NOTE-SIGNED-" + c.id()).contains("RX-SIGNED-" + c.id()).contains("INV-SIGNED-" + c.id())
                .as("drafts stay in the clinic").doesNotContain("NOTE-DRAFT-").doesNotContain("RX-DRAFT-")
                .doesNotContain("NEIGHBOUR-").doesNotContain("OTHERCLINIC-").doesNotContain(other.id().toString()).doesNotContain(neighbour.toString());
        Map<String, Object> bundle = objectMapper.readValue(json, Map.class);
        assertThat(bundle).containsEntry("patientId", p.toString()).containsEntry("requestId", id);
        Map<String, Map<String, List<Map<String, Object>>>> modules = (Map) bundle.get("modules");
        assertThat(modules.get("clinical").get("clinical_notes")).hasSize(1).allSatisfy(n -> assertThat(n).containsEntry("status", "signed"));
        assertThat(modules.get("clinical").get("prescriptions")).hasSize(1).allSatisfy(n -> assertThat(n).containsEntry("status", "signed"));
        assertThat(modules.get("clinical").get("prescription_items")).hasSize(1);
        assertThat(modules.get("clinical").get("vitals")).as("vital signs are not signed documents: all of them").hasSize(2);
        assertThat(modules.get("queue").get("queue_entries")).hasSize(2);
        assertThat(modules.get("billing").get("invoices")).hasSize(2);
        assertThat(modules.get("patient").get("patients")).hasSize(1).first().satisfies(me -> assertThat(me).containsEntry("name", "Asha Rao").doesNotContainKeys("national_id_enc", "name_search"));
        assertThat(modules.get("patient").get("consents")).hasSize(1);
        // every row of every table belongs to this clinic
        modules.values().forEach(tables -> tables.values().forEach(rows -> rows.forEach(r -> assertThat(r.get("tenant_id")).isEqualTo(c.id().toString()))));
        // who accessed the record
        List<Map<String, Object>> access = (List<Map<String, Object>>) bundle.get("accessLog");
        assertThat(access).hasSize(1).first().satisfies(a -> assertThat(a).containsEntry("by", "Test Staff").containsEntry("role", "Owner").containsEntry("what", "clinical_note"));

        // the manifest names every registry table (see Plt047ExportRegistryTest for the registry itself) and every file with its hash
        Map<String, Object> manifest = objectMapper.readValue(zip.get("manifest.json"), Map.class);
        List<Map<String, Object>> tables = (List<Map<String, Object>>) manifest.get("tables");
        assertThat(tables).extracting(t -> t.get("table")).contains("clinical_notes", "prescriptions", "invoices", "consents", "phi_read_log", "appointments", "whatsapp_messages");
        assertThat(tables).allSatisfy(t -> assertThat(t.containsKey("rows") || t.containsKey("notExported")).isTrue());
        assertThat(tables.stream().filter(t -> "clinical_notes".equals(t.get("table"))).findFirst().orElseThrow()).containsEntry("rows", 1);
        assertThat((List<Map<String, Object>>) manifest.get("files")).extracting(f -> f.get("name")).containsExactly("data.json", "summary.pdf");

        // one code, one open: the same code does not work twice; every open is audited
        assertThat(open(l.token(), "424242").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'dsr.downloaded' AND entity_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        // three opens at most, and nothing after 72 hours
        su.update("UPDATE secure_links SET open_count = 3 WHERE id = ?", l.id());
        setOtp(l.id(), "515151");
        assertThat(open(l.token(), "515151").getStatusCode()).isEqualTo(HttpStatus.GONE);
        su.update("UPDATE secure_links SET open_count = 1, expires_at = now() - interval '1 minute' WHERE id = ?", l.id());
        setOtp(l.id(), "616161");
        assertThat(open(l.token(), "616161").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void aGuardiansPackageGoesToTheGuardianAndStopsOpeningWhenTheGuardianLinkEnds() throws Exception {
        Clinic c = clinic(true);
        String ph = phone();
        UUID mother = patient(c.id(), "Meera Rao", ph, "1985-01-01", null, true);
        UUID child = patient(c.id(), "Aarav Rao", ph, java.time.LocalDate.now().minusYears(6).toString(), mother, false);
        visit(c, child, 1, true, "CHILD-" + c.id());
        String id = request(c, Map.of("patientId", child.toString(), "requesterPatientId", mother.toString(), "type", "access"));
        runWorker(c);
        assertThat(row(id)).containsEntry("status", "completed");

        Link l = link(id);
        assertThat(l.params().get(0)).as("addressed to the guardian").isEqualTo("Meera");
        assertThat(su.queryForMap("SELECT patient_id, recipient_patient_id FROM secure_links WHERE id = ?", l.id())).containsEntry("patient_id", child).containsEntry("recipient_patient_id", mother);
        assertThat(new String(download(l, "121212").get("data.json"), StandardCharsets.UTF_8)).contains("NOTE-CHILD-" + c.id()).doesNotContain("Meera");

        // a link to the same package minted for someone else does not open it (the package is bound to its recipient)
        UUID stranger = patient(c.id(), "Someone Else", phone(), "1970-01-01", null, true);
        byte[] raw = new byte[48];
        new java.security.SecureRandom().nextBytes(raw);
        java.nio.ByteBuffer.wrap(raw).putLong(c.id().getMostSignificantBits()).putLong(c.id().getLeastSignificantBits());
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID forgedId = su.queryForObject("INSERT INTO secure_links (tenant_id, token_hash, document_type, document_id, patient_id, recipient_patient_id, expires_at, created_by) "
                + "VALUES (?,?,'data_export',?::uuid,?,?, now() + interval '1 day', gen_random_uuid()) RETURNING id", UUID.class, c.id(), sha(forged), id, child, stranger);
        setOtp(forgedId, "343434");
        ResponseEntity<Map> stolen = open(forged, "343434");
        assertThat(stolen.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(stolen.getBody()).doesNotContainKey("dataExport");

        // the guardian link ends after the package was sent: the link she already holds stops working at once
        su.update("UPDATE patients SET guardian_id = NULL WHERE id = ?", child);
        setOtp(l.id(), "565656");
        ResponseEntity<Map> after = open(l.token(), "565656");
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(after.getBody()).doesNotContainKey("dataExport");
    }

    @Test
    void aRequestWhoseGuardianLinkEndedBeforeTheBuildIsRejectedAndNothingIsBuilt() {
        Clinic c = clinic(true);
        String ph = phone();
        UUID mother = patient(c.id(), "Meera Rao", ph, "1985-01-01", null, true);
        UUID child = patient(c.id(), "Aarav Rao", ph, java.time.LocalDate.now().minusYears(6).toString(), mother, false);
        // held back from the worker until the link is gone, so the order of events is certain
        String id = request(c, Map.of("patientId", child.toString(), "requesterPatientId", mother.toString(), "type", "correction"));
        su.update("UPDATE patients SET guardian_id = NULL WHERE id = ?", child);
        su.update("UPDATE dsr_requests SET type = 'access' WHERE id = ?::uuid", id);
        runWorker(c);
        assertThat(row(id)).containsEntry("status", "rejected").containsEntry("closure_reason", "requester_not_authorised").containsEntry("export_file_id", null).containsEntry("export_link_id", null);
        assertThat(su.queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id = ?", Integer.class, c.id())).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM notification_requests WHERE tenant_id = ?", Integer.class, c.id())).isZero();
    }

    @Test
    void aFailedDeliveryIsNeverMarkedDoneAndIsRetriedAfterTheBackOff() {
        Clinic c = clinic(false); // no approved secure_link template: the link cannot be sent
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null, true);
        String id = request(c, Map.of("patientId", p.toString(), "type", "access"));
        runWorker(c);

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "in_progress").containsEntry("export_file_id", null).containsEntry("completed_at", null);
        assertThat(su.queryForList("SELECT event FROM dsr_request_events WHERE request_id = ?::uuid ORDER BY id", String.class, id)).as("the build rolled back as a whole; one failure row")
                .containsExactly("received", "export_failed");
        assertThat(su.queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id = ?", Integer.class, c.id())).as("no half-issued link").isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'patient.dsr.export_ready.v1'", Integer.class, c.id())).isZero();
        // inside the back-off nothing is claimed again
        runWorker(c);
        assertThat(row(id)).containsEntry("export_attempts", 1);

        // the clinic's template is approved and the back-off has passed: the same request is delivered
        template(c.id());
        su.update("UPDATE dsr_requests SET export_started_at = now() - interval '16 minutes' WHERE id = ?::uuid", id);
        runWorker(c);
        assertThat(row(id)).containsEntry("status", "completed").containsEntry("export_attempts", 2);
        assertThat(row(id).get("export_file_id")).isNotNull();
    }

    @Test
    void withoutMessagingConsentNothingIsSentAndTheRequestStaysOpen() {
        Clinic c = clinic(true);
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null, false);
        String id = request(c, Map.of("patientId", p.toString(), "type", "access"));
        runWorker(c);
        assertThat(row(id)).containsEntry("status", "in_progress").containsEntry("export_file_id", null);
        assertThat(su.queryForObject("SELECT note FROM dsr_request_events WHERE request_id = ?::uuid AND event = 'export_failed'", String.class, id)).contains("has not agreed to receive messages");
        assertThat(su.queryForObject("SELECT count(*) FROM notification_requests WHERE tenant_id = ?", Integer.class, c.id())).isZero();
    }
}
