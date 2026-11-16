package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLN-023 over HTTP: the signed Rx PDF (stored once, same bytes and hash on every download), the token issued at sign and printed as the
 * public verify link, the public verify page's answer (valid, dispensed, revoked; doctor and counts only, no PHI), the published keys,
 * the per-IP rate limit, revocation by the prescriber only (pharmacy scan and checkout then refuse it) and its event.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Cln023RxPdfApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 400_000);

    @Autowired
    private StockCommands stock;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private PublicRxController publicRx;

    record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "c23" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        su().update("UPDATE staff SET name = 'Dr Rao' WHERE id = ?", staff.id());
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "CLN-023 test")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(tenant, staff, token);
    }

    private UUID item(Clinic c, String name) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name,
                "isRx", false, "price", 10.00, "taxRatePercent", 12, "scheduleClass", "OTC", "inHouse", true)), Map.class);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) r.getBody().get("id"));
        inTenantTx(c.t(), () -> stock.receiveBatch(id, new BatchReceipt("B" + SEQ.incrementAndGet(), LocalDate.now().plusDays(400),
                new BigDecimal("12.00"), new BigDecimal("7.50"), 50, null, null, null), c.staff().id(), "GRN"));
        return id;
    }

    private String visit(Clinic c) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Pdf Patient", "phone", phone(),
                "dob", "1970-01-01", "gender", "female")), Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
    }

    private ResponseEntity<Map> save(Clinic c, String qe, Object[]... lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] l : lines) items.add(Map.of("drugName", l[0], "dosage", "1 tab", "frequency", "BD", "quantity", l[1], "quantityUnit", "tablets"));
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", items)), Map.class);
        assertThat(saved.getStatusCode()).as("rx %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        return saved;
    }

    private List<String> sign(Clinic c, String qe, Object[]... lines) {
        ResponseEntity<Map> saved = save(c, qe, lines);
        assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 5; i++) dispatcher.poll();
        List<String> ids = new ArrayList<>();
        for (Object o : (List<?>) saved.getBody().get("items")) ids.add((String) ((Map<?, ?>) o).get("id"));
        return ids;
    }

    private ResponseEntity<byte[]> pdf(String token, String qe, String size) {
        return exchange("/v1/clinical/prescriptions/" + qe + "/pdf" + (size == null ? "" : "?size=" + size), HttpMethod.GET, authed(token), byte[].class);
    }

    private Map<String, Object> header(Clinic c, String qe) {
        ResponseEntity<Map> r = exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> publicVerify(String token) {
        return exchange("/v1/public/rx/verify/" + token, HttpMethod.GET, HttpEntity.EMPTY, Map.class);
    }

    private String storedToken(Clinic c, Object prescriptionId) {
        return su().queryForObject("SELECT rx_token FROM prescription_rx_tokens WHERE tenant_id = ? AND prescription_id = ?::uuid", String.class, c.t(), prescriptionId);
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    // ---------------- AC1 + AC5: the PDF's content, stored once, the same bytes and hash on every download ----------------

    @Test
    void theSignedRxPdfCarriesTheRequiredFieldsAndEveryDownloadMatchesTheStoredHash() throws Exception {
        Clinic c = clinic();
        ResponseEntity<Map> lic = exchange("/v1/staff/" + c.staff().id() + "/clinical-licence", HttpMethod.PUT, authedJsonBody(c.token(),
                Map.of("isClinician", true, "registrationCouncil", "Karnataka Medical Council")), Map.class);
        assertThat(lic.getStatusCode()).as("%s", lic.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lic.getBody()).containsEntry("registrationCouncil", "Karnataka Medical Council").containsEntry("hasSignature", false);
        ResponseEntity<Map> badSig = exchange("/v1/staff/" + c.staff().id() + "/clinical-licence", HttpMethod.PUT, authedJsonBody(c.token(),
                Map.of("isClinician", true, "signatureFileId", UUID.randomUUID().toString())), Map.class);
        assertThat(badSig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(slug(badSig)).isEqualTo("signature-not-usable");

        String qe = visit(c);
        save(c, qe, new Object[]{"Pdf paracetamol 500 mg", 9}, new Object[]{"Pdf amoxicillin 500 mg", 10});
        ResponseEntity<byte[]> draft = pdf(c.token(), qe, null);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new String(draft.getBody(), StandardCharsets.UTF_8)).contains("NOT_SIGNED");
        assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<byte[]> first = pdf(c.token(), qe, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        String sha = sha256(first.getBody());
        assertThat(first.getHeaders().getFirst("X-Content-SHA256")).isEqualTo(sha);
        ResponseEntity<byte[]> again = pdf(c.token(), qe, "a4");
        assertThat(sha256(again.getBody())).as("downloaded twice, same hash").isEqualTo(sha);
        Map<String, Object> row = su().queryForMap("SELECT t.pdf_a4_sha256, f.purpose, f.patient_id IS NOT NULL AS bound, (SELECT count(*) FROM file_objects g "
                + "WHERE g.tenant_id = t.tenant_id AND g.purpose = 'prescription') AS stored FROM prescription_rx_tokens t JOIN file_objects f ON f.id = t.pdf_a4_file_id "
                + "WHERE t.tenant_id = ?", c.t());
        assertThat(row).containsEntry("pdf_a4_sha256", sha).containsEntry("purpose", "prescription").containsEntry("bound", true).containsEntry("stored", 1L);

        String text = new String(first.getBody(), StandardCharsets.ISO_8859_1);
        Map<String, Object> h = header(c, qe);
        assertThat(text).contains("(Test Clinic").contains("(Dr Rao)").contains("Reg. no: " + h.get("professionalRegistrationNo"))
                .contains("Council: Karnataka Medical Council").contains("Patient: Pdf Patient   Age/Sex: ").contains("y / female")
                .contains("1. Pdf paracetamol 500 mg").contains("2. Pdf amoxicillin 500 mg").contains("Issued: ").contains("Valid until: no expiry set")
                .contains("Allergies at signing:").contains("Scan to verify").contains(" re f\n");
        ResponseEntity<byte[]> a5 = pdf(c.token(), qe, "a5");
        assertThat(new String(a5.getBody(), StandardCharsets.ISO_8859_1)).contains("/MediaBox [0 0 419.53 595.28]");
        assertThat(sha256(a5.getBody())).isNotEqualTo(sha).isEqualTo(su().queryForObject("SELECT pdf_a5_sha256 FROM prescription_rx_tokens WHERE tenant_id = ?",
                String.class, c.t()));
        assertThat(pdf(c.token(), qe, "a3").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // the token was issued at sign, the printout shows that same token as the verify link, and it never changes
        String token = storedToken(c, h.get("prescriptionId"));
        assertThat(h.get("qrPayload")).isEqualTo(token);
        assertThat(header(c, qe).get("qrPayload")).isEqualTo(token);
        assertThat(h).containsEntry("registrationCouncil", "Karnataka Medical Council").containsEntry("revokedAt", null);
        String signed = su().queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'clinical.prescription.signed.v1'",
                String.class, c.t());
        assertThat(signed).contains("\"rxTokenRef\"").contains("\"validUntil\"").doesNotContain(token);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'prescription.pdf_stored'", Integer.class, c.t())).isEqualTo(2);

        // another clinic cannot fetch it
        Clinic other = clinic();
        assertThat(pdf(other.token(), qe, null).getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    // ---------------- AC2 + AC3: any phone sees Valid with doctor, reg no and line count; the keys are public ----------------

    @Test
    void anyPhoneSeesValidWithTheDoctorAndLineCountAndNothingAboutThePatient() {
        Clinic c = clinic();
        String qe = visit(c);
        sign(c, qe, new Object[]{"Pv drug one", 3}, new Object[]{"Pv drug two", 4});
        Map<String, Object> h = header(c, qe);
        String token = (String) h.get("qrPayload");
        publicRx.resetLimits();

        ResponseEntity<Map> v = publicVerify(token);
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(v.getBody().keySet()).containsExactlyInAnyOrder("status", "doctor", "issuedAt", "validUntil", "lineCount", "dispensedAt");
        assertThat(v.getBody()).containsEntry("status", "valid").containsEntry("lineCount", 2).containsEntry("validUntil", null).containsEntry("dispensedAt", null);
        assertThat((Map<String, Object>) v.getBody().get("doctor")).containsEntry("name", "Dr Rao").containsEntry("registrationNo", h.get("professionalRegistrationNo"));
        assertThat(v.getBody().toString()).doesNotContain("Pdf Patient").doesNotContain("Pv drug");
        assertThat(v.getHeaders().getCacheControl()).contains("no-store");
        // the scanned link form and a bearer-less call both work; a tampered or unknown token is the same 404
        assertThat(publicVerify("x" + token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String[] f = token.split("\\.", -1);
        f[4] = "4102444800"; // validity stretched to 2100
        ResponseEntity<Map> tampered = publicVerify(String.join(".", f));
        assertThat(tampered.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(slug(tampered)).isEqualTo("UNKNOWN_TOKEN");
        assertThat(publicVerify("NRX1.%2e%2e.%00").getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        assertThat(publicVerify("a".repeat(1200)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> keys = exchange("/v1/public/rx/keys", HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertThat(keys.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> jwks = (List<Map<String, Object>>) keys.getBody().get("keys");
        assertThat(jwks).isNotEmpty().allSatisfy(k -> assertThat(k).containsEntry("kty", "EC").containsEntry("crv", "P-256").containsKey("kid").doesNotContainKey("d"));
        String kid = new String(java.util.Base64.getUrlDecoder().decode(f[5]), StandardCharsets.UTF_8);
        assertThat(jwks).extracting(k -> k.get("kid")).contains(kid);
    }

    // ---------------- AC4: fully dispensed in the clinic's pharmacy -> "Already fully dispensed on <date>" ----------------

    @Test
    void aFullyDispensedRxReadsDispensedWithTheDate() {
        Clinic c = clinic();
        UUID drug = item(c, "Pd drug");
        String qe = visit(c);
        List<String> rx = sign(c, qe, new Object[]{"Pd drug", 4});
        String token = (String) header(c, qe).get("qrPayload");
        String code = su().queryForObject("SELECT code FROM charge_catalogue WHERE id = ?", String.class, drug);
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> bill = exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", code, "quantity", 4, "prescriptionItemId", rx.get(0))))), Map.class);
        assertThat(bill.getStatusCode()).as("%s", bill.getBody()).isEqualTo(HttpStatus.OK);
        publicRx.resetLimits();
        ResponseEntity<Map> v = publicVerify(token);
        assertThat(v.getBody()).containsEntry("status", "dispensed");
        assertThat(Instant.parse((String) v.getBody().get("dispensedAt"))).isAfter(Instant.now().minus(Duration.ofMinutes(5)));
        assertThat(v.getBody().toString()).doesNotContain((String) bill.getBody().get("invoiceNumber"));
    }

    // ---------------- revoke: prescriber only, once, with a reason; scans and checkout then refuse it ----------------

    @Test
    void onlyTheLicensedPrescriberRevokesOnceAndEveryCheckThenRefusesTheRx() {
        Clinic c = clinic();
        UUID drug = item(c, "Rv drug");
        String qe = visit(c);
        List<String> rx = sign(c, qe, new Object[]{"Rv drug", 2});
        Map<String, Object> h = header(c, qe);
        String token = (String) h.get("qrPayload");

        // a nurse-like role (clinical edit, no approve, not a licensed clinician) and an unlicensed full-access user are refused
        UUID nurseRole = seedRole(c.t(), "Nurse " + SEQ.incrementAndGet(), false, new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        String nurse = loginAndGetAccessToken(seedStaff(c.tenant(), nurseRole, "n" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        ResponseEntity<Map> refused = exchange("/v1/clinical/prescriptions/" + qe + "/revoke", HttpMethod.POST, authedJsonBody(nurse, Map.of("reason", "Wrong patient")), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/revoke", HttpMethod.POST, authedJsonBody(c.token(), Map.of("reason", "no")), Map.class)
                .getStatusCode()).as("reason too short").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> ok = exchange("/v1/clinical/prescriptions/" + qe + "/revoke", HttpMethod.POST, authedJsonBody(c.token(), Map.of("reason", "Dose entered wrongly")), Map.class);
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "revoked").containsKey("revokedAt");
        ResponseEntity<Map> twice = exchange("/v1/clinical/prescriptions/" + qe + "/revoke", HttpMethod.POST, authedJsonBody(c.token(), Map.of("reason", "Again please")), Map.class);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(slug(twice)).isEqualTo("prescription-revoked");
        assertThat(su().queryForObject("SELECT status FROM prescriptions WHERE id = ?::uuid", String.class, h.get("prescriptionId"))).as("V51: signed row untouched").isEqualTo("signed");
        assertThat(header(c, qe)).containsEntry("revokeReason", "Dose entered wrongly");

        publicRx.resetLimits();
        assertThat(publicVerify(token).getBody()).containsEntry("status", "revoked");
        String ph = loginAndGetAccessToken(seedStaff(c.tenant(), seedRole(c.t(), "Pharmacist " + SEQ.incrementAndGet(), false,
                new ModuleGrant("pharmacy", true, true, true, false, false, false, false)), "ph" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        ResponseEntity<Map> scan = exchange("/v1/pharmacy/rx-verifications", HttpMethod.POST, authedJsonBody(ph, Map.of("qrPayload", "http://localhost:3810/rx/" + token)), Map.class);
        assertThat(scan.getBody()).containsEntry("status", "invalid").containsEntry("reason", "revoked").containsEntry("prescriber", null);

        String code = su().queryForObject("SELECT code FROM charge_catalogue WHERE id = ?", String.class, drug);
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> bill = exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", code, "quantity", 1, "prescriptionItemId", rx.get(0))))), Map.class);
        assertThat(bill.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(bill)).isEqualTo("rx-revoked");

        String env = su().queryForObject("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'clinical.prescription.revoked.v1'",
                String.class, c.t());
        assertThat(env).contains((String) h.get("prescriptionId")).doesNotContain("Dose entered wrongly");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'prescription.revoked'", Integer.class, c.t())).isEqualTo(1);
        assertThatTokenRowIsWriteOnce(c, (String) h.get("prescriptionId"));
    }

    private void assertThatTokenRowIsWriteOnce(Clinic c, String prescriptionId) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> su().update("UPDATE prescription_rx_tokens SET revoked_at = NULL, revoke_reason = NULL, revoked_by = NULL "
                + "WHERE prescription_id = ?::uuid", prescriptionId)).hasMessageContaining("write-once");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> su().update("DELETE FROM prescription_rx_tokens WHERE prescription_id = ?::uuid", prescriptionId))
                .hasMessageContaining("cannot be deleted");
    }

    // ---------------- send to patient: an OTP secure link to the stored PDF ----------------

    @Test
    void sendingThePdfIssuesASecureLinkToTheStoredFile() {
        Clinic c = clinic();
        String qe = visit(c);
        sign(c, qe, new Object[]{"Sd drug", 2});
        inTenantTx(c.t(), () -> new JdbcTemplate(superuserDataSource()).update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'secure_link', ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, a secure message is ready: {{2}}', 2, 'approved')""",
                c.t(), "m_" + UUID.randomUUID().toString().substring(0, 8)));
        ResponseEntity<Map> sent = exchange("/v1/clinical/prescriptions/" + qe + "/pdf/send", HttpMethod.POST, authedJsonBody(c.token(), Map.of("language", "en")), Map.class);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> link = su().queryForMap("SELECT s.document_type, f.purpose FROM secure_links s JOIN file_objects f ON f.id = s.document_id WHERE s.id = ?::uuid",
                sent.getBody().get("linkId"));
        assertThat(link).containsEntry("document_type", "document").containsEntry("purpose", "prescription");
    }

    // ---------------- rate limit: 30 a minute per IP, then 429 ----------------

    @Test
    void thePublicVerifyIsLimitedToThirtyAMinutePerIp() {
        publicRx.resetLimits();
        for (int i = 0; i < PublicRxController.PER_MINUTE; i++) {
            assertThat(publicVerify("NRX1.unknown" + i).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Map> limited = publicVerify("NRX1.unknown");
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(slug(limited)).isEqualTo("RATE_LIMITED");
        publicRx.resetLimits();
    }
}
