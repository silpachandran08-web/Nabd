package com.nabd.hms.nursing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.files.ObjectStorage;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLN-014: consent forms, signed consent PDF + hash, guardian rule, completion gate, withdrawal. */
class Cln014ProcedureConsentApiTest extends ApiTestBase {

    private static final String SIG = "<svg viewBox=\"0 0 600 200\"><path d=\"M10 10 L40 30 L70 50 L100 50 L130 60 L160 70 L200 80\"/>"
            + "<path d=\"M300 20 L310 30 L320 40 L330 45\"/></svg>";

    @Autowired
    ObjectStorage storage;

    private record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private Clinic clinic(String n) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "cln14-" + n + "@a.com", "+9198000140" + n, false);
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?,?,?,?,?,?)",
                tenant.id(), "PEEL", "Chemical peel", "Cosmetic", 3000, 18));
        return new Clinic(tenant, staff, loginAndGetAccessToken(staff));
    }

    private String patient(Clinic c, String name, String phone, String dob, String guardianId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "phone", phone, "dob", dob, "gender", "female"));
        if (guardianId != null) {
            body.put("guardianId", guardianId);
            body.put("guardianConsent", Map.of("confirmed", true, "channel", "in_person"));
        }
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String order(Clinic c, String patientId) {
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staff().id().toString())), Map.class);
        ResponseEntity<Map> o = exchange("/v1/nursing/procedure-orders", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "queueEntryId", q.getBody().get("id"), "chargeCode", "PEEL")), Map.class);
        assertThat(o.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) o.getBody().get("id");
    }

    private ResponseEntity<Map> addTemplate(Clinic c, String lang, String body) {
        return exchange("/v1/nursing/consent-templates", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "chargeCode", "PEEL", "lang", lang, "title", "Chemical peel consent", "bodyHtml", body)), Map.class);
    }

    private Map<String, Object> signed(Map<?, ?> template, String kind, String guardianId, String name, UUID witness) {
        Map<String, Object> signer = new HashMap<>(Map.of("kind", kind, "name", name));
        if (guardianId != null) signer.put("guardianId", guardianId);
        return Map.of("templateId", template.get("id"), "templateVersion", template.get("version"), "signer", signer,
                "signatureSvg", SIG, "witnessStaffId", witness.toString(), "lang", "en");
    }

    private ResponseEntity<Map> capture(Clinic c, String orderId, Map<String, Object> body) {
        return exchange("/v1/nursing/procedure-orders/" + orderId + "/consent", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private ResponseEntity<Map> status(Clinic c, String orderId, String status) {
        return exchange("/v1/nursing/procedure-orders/" + orderId + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", status)), Map.class);
    }

    private static org.springframework.http.HttpEntity<Void> pdfAccept(String token) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(token);
        h.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_PDF, org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON));
        return new org.springframework.http.HttpEntity<>(h);
    }

    @Test
    void theActiveVersionInThePatientsLanguageIsShownWithItsRisks() {
        Clinic c = clinic("01");
        assertThat(addTemplate(c, "en", "<p>Risks: redness v1</p>").getBody().get("version")).isEqualTo(1);
        ResponseEntity<Map> v2 = addTemplate(c, "en", "<p>Risks: redness, peeling v2</p>");
        assertThat(v2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(v2.getBody().get("version")).isEqualTo(2);
        addTemplate(c, "hi", "<p>hi risks</p>");

        List<Map<String, Object>> en = templates(c, "?chargeCode=PEEL&lang=en");
        assertThat(en).hasSize(1);
        assertThat(en.get(0)).containsEntry("version", 2).containsEntry("lang", "en").containsEntry("placeholder", false);
        assertThat((String) en.get(0).get("bodyHtml")).contains("peeling v2");
        assertThat(templates(c, "?chargeCode=PEEL").stream().map(t -> t.get("lang"))).containsExactlyInAnyOrder("en", "hi");

        // a clinic with no form of its own sees only the clearly marked starter placeholder
        Clinic other = clinic("02");
        List<Map<String, Object>> starter = templates(other, "?chargeCode=PEEL&lang=en");
        assertThat(starter).singleElement().satisfies(t -> {
            assertThat(t).containsEntry("placeholder", true);
            assertThat((String) t.get("title")).startsWith("PLACEHOLDER");
        });
        // and cannot have a patient sign it
        String orderId = order(other, patient(other, "Adult", "+919999141002", "1990-01-01", null));
        ResponseEntity<Map> refused = capture(other, orderId, signed(starter.get(0), "patient", null, "Adult", other.staff().id()));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("type")).asString().contains("TEMPLATE_NOT_APPROVED");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> templates(Clinic c, String query) {
        ResponseEntity<Map> r = exchange("/v1/nursing/consent-templates" + query, HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    @Test
    void aMinorNeedsTheLinkedGuardiansSignatureAndIsBlockedWithoutIt() {
        Clinic c = clinic("03");
        Map<?, ?> t = addTemplate(c, "en", "<p>Risks: redness</p>").getBody();
        String guardian = patient(c, "Parent", "+919999141003", "1980-01-01", null);
        String stranger = patient(c, "Stranger", "+919999141004", "1981-01-01", null);
        String child = patient(c, "Teen", "+919999141005", LocalDate.now().minusYears(15).toString(), guardian);
        String orderId = order(c, child);

        ResponseEntity<Map> self = capture(c, orderId, signed(t, "patient", null, "Teen", c.staff().id()));
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(self.getBody().get("type")).asString().contains("GUARDIAN_REQUIRED");
        ResponseEntity<Map> wrong = capture(c, orderId, signed(t, "guardian", stranger, "Stranger", c.staff().id()));
        assertThat(wrong.getBody().get("type")).asString().contains("GUARDIAN_REQUIRED");
        ResponseEntity<Map> blocked = status(c, orderId, "completed");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("type")).asString().contains("CONSENT_REQUIRED");

        // the guardian signs; the teen's assent is optional (none sent here)
        ResponseEntity<Map> ok = capture(c, orderId, signed(t, "guardian", guardian, "Parent", c.staff().id()));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(status(c, orderId, "completed").getBody().get("status")).isEqualTo("completed");
    }

    @Test
    void theSignedPdfIsStoredWithItsHashAndEveryDownloadReturnsTheSameHash() throws Exception {
        Clinic c = clinic("04");
        Map<?, ?> t = addTemplate(c, "en", "<p>Risks: redness &amp; peeling</p>").getBody();
        String orderId = order(c, patient(c, "Adult", "+919999141006", "1990-01-01", null));

        ResponseEntity<Map> r = capture(c, orderId, signed(t, "patient", null, "Adult", c.staff().id()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String consentId = (String) r.getBody().get("consentId");
        String sha = (String) r.getBody().get("sha256");
        assertThat(sha).matches("[0-9a-f]{64}");
        assertThat(r.getBody().get("pdfDocumentId")).isNotNull().isNotEqualTo(consentId);
        assertThat((Map<String, Object>) r.getBody().get("order")).containsEntry("consentId", consentId).containsEntry("consentSha256", sha);

        ResponseEntity<byte[]> d1 = exchange("/v1/nursing/procedure-consents/" + consentId + "/pdf", HttpMethod.GET, authed(c.token()), byte[].class);
        ResponseEntity<byte[]> d2 = exchange("/v1/nursing/procedure-consents/" + consentId + "/pdf", HttpMethod.GET, authed(c.token()), byte[].class);
        assertThat(d1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(d1.getHeaders().getFirst("X-Content-SHA256")).isEqualTo(sha);
        assertThat(d2.getHeaders().getFirst("X-Content-SHA256")).isEqualTo(sha);
        assertThat(ConsentService.sha256(d1.getBody())).isEqualTo(sha);
        assertThat(d2.getBody()).isEqualTo(d1.getBody());
        String text = new String(d1.getBody(), StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-").contains("PROCEDURE CONSENT").contains("Risks: redness & peeling")
                .contains("<p>Risks: redness &amp; peeling</p>") // the exact form text, attached
                .contains("Signed by: Adult \\(patient\\)"); // PDF string escapes

        // the row is append-only: the hash cannot be rewritten
        assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE procedure_consents SET pdf_sha256 = ? WHERE id = ?::uuid",
                "0".repeat(64), consentId))).hasMessageContaining("append-only");
        // tampered bytes in storage are caught on download
        String key = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT f.storage_key FROM procedure_consents p JOIN file_objects f ON f.id = p.pdf_file_id "
                + "WHERE p.id = ?::uuid", String.class, consentId));
        storage.put(ObjectStorage.Bucket.CLEAN, key, "%PDF-1.4 tampered".getBytes(StandardCharsets.ISO_8859_1));
        ResponseEntity<Map> tampered = exchange("/v1/nursing/procedure-consents/" + consentId + "/pdf", HttpMethod.GET, pdfAccept(c.token()), Map.class);
        assertThat(tampered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tampered.getBody().get("type")).asString().contains("PDF_INTEGRITY_FAILED");

        // another clinic cannot read it
        Clinic other = clinic("05");
        assertThat(exchange("/v1/nursing/procedure-consents/" + consentId + "/pdf", HttpMethod.GET, pdfAccept(other.token()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // the capture is audited with the hash
        String audit = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'nursing.procedure_consent_signed'", String.class, c.tenant().id()));
        assertThat(audit).contains(sha).contains(consentId);
    }

    @Test
    void completingWithoutValidConsentIs409AndTheTypedNameIsRefusedOnceAFormExists() {
        Clinic c = clinic("06");
        Map<?, ?> t1 = addTemplate(c, "en", "<p>v1</p>").getBody();
        String orderId = order(c, patient(c, "Adult", "+919999141007", "1990-01-01", null));

        ResponseEntity<Map> typed = capture(c, orderId, Map.of("signedName", "Adult"));
        assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(typed.getBody().get("type")).asString().contains("TEMPLATE_REQUIRED");
        ResponseEntity<Map> blocked = status(c, orderId, "completed");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("type")).asString().contains("CONSENT_REQUIRED");

        // a form signed against an older version is stale
        addTemplate(c, "en", "<p>v2</p>");
        ResponseEntity<Map> stale = capture(c, orderId, signed(t1, "patient", null, "Adult", c.staff().id()));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(stale.getBody().get("type")).asString().contains("TEMPLATE_VERSION_STALE");

        // the database backstop: even a direct update cannot complete it without a signed form
        assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> jdbc.update(
                "UPDATE procedure_orders SET consent_signed_at = now(), consent_signed_name = 'x', status = 'completed' WHERE id = ?::uuid", orderId)))
                .hasMessageContaining("procedure_orders_consent_required");
    }

    @Test
    void aWithdrawalBlocksTheOrderIsFinalAndIsReported() {
        Clinic c = clinic("07");
        Map<?, ?> t = addTemplate(c, "en", "<p>Risks</p>").getBody();
        String orderId = order(c, patient(c, "Adult", "+919999141008", "1990-01-01", null));
        assertThat(capture(c, orderId, signed(t, "patient", null, "Adult", c.staff().id())).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> w = exchange("/v1/nursing/procedure-orders/" + orderId + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "Patient changed their mind")), Map.class);
        assertThat(w.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(w.getBody()).containsEntry("status", "withdrawn");
        assertThat(exchange("/v1/nursing/procedure-orders/" + orderId + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "again")), Map.class).getBody()).containsEntry("status", "withdrawn");

        assertThat(status(c, orderId, "prepped").getBody().get("type")).asString().contains("CONSENT_REQUIRED");
        ResponseEntity<Map> again = capture(c, orderId, signed(t, "patient", null, "Adult", c.staff().id()));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("type")).asString().contains("CONSENT_WITHDRAWN");

        ResponseEntity<List> today = exchange("/v1/nursing/procedure-orders/today", HttpMethod.GET, authed(c.token()), List.class);
        Map<?, ?> row = (Map<?, ?>) today.getBody().stream().filter(o -> orderId.equals(((Map<?, ?>) o).get("id"))).findFirst().orElseThrow();
        assertThat(row.get("consentWithdrawnAt")).isNotNull();
        assertThat(row.get("consentWithdrawReason")).isEqualTo("Patient changed their mind");
        String consentStatus = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT status FROM procedure_consents WHERE procedure_order_id = ?::uuid", String.class, orderId));
        assertThat(consentStatus).isEqualTo("withdrawn");
        Integer audits = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'nursing.procedure_consent_withdrawn'", Integer.class, c.tenant().id()));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void addingAFormNeedsClinicalApproveAndAReplayedCaptureRunsOnce() {
        Clinic c = clinic("08");
        UUID nurseRole = seedRole(c.tenant().id(), "Nurse", false, new ModuleGrant("nursing", true, true, true, false, false, false, false),
                new ModuleGrant("queue", true, true, true, false, false, false, false), new ModuleGrant("patients", true, true, true, false, false, false, false));
        SeededStaff nurse = seedStaff(c.tenant(), nurseRole, "cln14-nurse@a.com", "+919800014099", false);
        String nurseToken = loginAndGetAccessToken(nurse);
        assertThat(exchange("/v1/nursing/consent-templates", HttpMethod.POST, authedJsonBody(nurseToken, Map.of(
                "chargeCode", "PEEL", "lang", "en", "title", "x", "bodyHtml", "y")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/nursing/consent-templates?chargeCode=PEEL", HttpMethod.GET, authed(nurseToken), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<?, ?> t = addTemplate(c, "en", "<p>Risks</p>").getBody();
        String orderId = order(c, patient(c, "Adult", "+919999141009", "1990-01-01", null));
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = exchange("/v1/nursing/procedure-orders/" + orderId + "/consent", HttpMethod.POST,
                authedJsonBodyWithHeader(nurseToken, "Idempotency-Key", key, signed(t, "patient", null, "Adult", nurse.id())), Map.class);
        ResponseEntity<Map> replay = exchange("/v1/nursing/procedure-orders/" + orderId + "/consent", HttpMethod.POST,
                authedJsonBodyWithHeader(nurseToken, "Idempotency-Key", key, signed(t, "patient", null, "Adult", nurse.id())), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("consentId")).isEqualTo(first.getBody().get("consentId"));
        Integer rows = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM procedure_consents WHERE procedure_order_id = ?::uuid", Integer.class, orderId));
        assertThat(rows).isEqualTo(1);
    }


    @Test
    void aDotOrAShortTapIsNotASignature() {
        Clinic c = clinic("09");
        Map<?, ?> t = addTemplate(c, "en", "<p>Risks</p>").getBody();
        String orderId = order(c, patient(c, "Adult", "+919999141010", "1990-01-01", null));
        for (String svg : List.of("<svg><path d=\"M0 0\"/></svg>", "<svg><path d=\"M100 100 L102 101 L103 101\"/></svg>",
                "<svg><path d=\"M100 100 L101 100 L102 100 L103 100 L104 100 L105 100 L106 100 L107 100 L108 100 L109 100 L110 100\"/></svg>")) {
            Map<String, Object> body = new HashMap<>(signed(t, "patient", null, "Adult", c.staff().id()));
            body.put("signatureSvg", svg);
            ResponseEntity<Map> r = capture(c, orderId, body);
            assertThat(r.getStatusCode()).as(svg).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("type")).asString().contains("SIGNATURE_REQUIRED");
        }
        assertThat(capture(c, orderId, signed(t, "patient", null, "Adult", c.staff().id())).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theOrderTellsTheDialogTheMinorAndGuardianAndTheGuardianIdMayBeOmitted() {
        Clinic c = clinic("10");
        Map<?, ?> t = addTemplate(c, "en", "<p>Risks</p>").getBody();
        String guardian = patient(c, "Parent Ten", "+919999141011", "1980-01-01", null);
        String child = patient(c, "Teen Ten", "+919999141012", LocalDate.now().minusYears(15).toString(), guardian);
        String childOrder = order(c, child);
        String adultOrder = order(c, patient(c, "Adult Ten", "+919999141013", "1990-01-01", null));
        ResponseEntity<List> today = exchange("/v1/nursing/procedure-orders/today", HttpMethod.GET, authed(c.token()), List.class);
        Map<?, ?> childRow = row(today, childOrder);
        Map<?, ?> adultRow = row(today, adultOrder);
        assertThat(childRow.get("patientMinor")).isEqualTo(true);
        assertThat(childRow.get("guardianName")).isEqualTo("Parent Ten");
        assertThat(adultRow.get("patientMinor")).isEqualTo(false);
        assertThat(adultRow.get("guardianName")).isNull();

        // a nurse who cannot read the patient record signs as "guardian" without a guardianId: the linked guardian is used
        ResponseEntity<Map> ok = capture(c, childOrder, signed(t, "guardian", null, "Parent Ten", c.staff().id()));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String stored = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT guardian_id::text FROM procedure_consents WHERE procedure_order_id = ?::uuid", String.class, childOrder));
        assertThat(stored).isEqualTo(guardian);
        // no linked guardian: still 409
        ResponseEntity<Map> none = capture(c, adultOrder, signed(t, "guardian", null, "Someone", c.staff().id()));
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(none.getBody().get("type")).asString().contains("GUARDIAN_REQUIRED");
    }

    private static Map<?, ?> row(ResponseEntity<List> list, String id) {
        return (Map<?, ?>) list.getBody().stream().filter(o -> id.equals(((Map<?, ?>) o).get("id"))).findFirst().orElseThrow();
    }
}
