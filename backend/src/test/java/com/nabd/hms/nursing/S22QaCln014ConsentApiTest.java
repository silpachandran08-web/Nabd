package com.nabd.hms.nursing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S22 QA (t1), CLN-014: a consent form is bound to its procedure, its clinic and its order (another form, clinic or a missing grant
 * is refused); a replayed capture creates one consent; after a withdrawal neither start nor a new capture is possible; the guardian
 * rule for a minor; signature data at the trust boundary (empty, script, a single dot).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S22QaCln014ConsentApiTest extends ApiTestBase {

    private static final String SIG = "<svg viewBox=\"0 0 600 200\"><path d=\"M10 10 L30 20 L50 35 L70 30 L90 50 L110 45 L130 60 L150 55 L170 70 L190 65 L210 80\"/></svg>";
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    private record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "qa22-cl-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        inTenantTx(tenant.id(), () -> {
            jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?,?,?,?,?,?)",
                    tenant.id(), "PEEL", "Chemical peel", "Cosmetic", 3000, 18);
            jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?,?,?,?,?,?)",
                    tenant.id(), "LASER", "Laser hair removal", "Cosmetic", 5000, 18);
        });
        return new Clinic(tenant, staff, loginAndGetAccessToken(staff));
    }

    private String patient(Clinic c, String dob, String guardianId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "QA Patient " + SEQ.incrementAndGet(), "phone", phone(), "dob", dob, "gender", "female"));
        if (guardianId != null) {
            body.put("guardianId", guardianId);
            body.put("guardianConsent", Map.of("confirmed", true, "channel", "in_person"));
        }
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String order(Clinic c, String patientId, String code) {
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staff().id().toString())), Map.class);
        ResponseEntity<Map> o = exchange("/v1/nursing/procedure-orders", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "queueEntryId", q.getBody().get("id"), "chargeCode", code)), Map.class);
        assertThat(o.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) o.getBody().get("id");
    }

    private Map<String, Object> template(Clinic c, String code) {
        ResponseEntity<Map> r = exchange("/v1/nursing/consent-templates", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "chargeCode", code, "lang", "en", "title", code + " consent", "bodyHtml", "<p>Risks for " + code + "</p>")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private Map<String, Object> body(Map<?, ?> template, String kind, String guardianId, UUID witness, String svg) {
        Map<String, Object> signer = new HashMap<>(Map.of("kind", kind, "name", "QA Signer"));
        if (guardianId != null) signer.put("guardianId", guardianId);
        Map<String, Object> b = new HashMap<>(Map.of("templateId", template.get("id"), "templateVersion", template.get("version"), "signer", signer,
                "witnessStaffId", witness.toString(), "lang", "en"));
        if (svg != null) b.put("signatureSvg", svg);
        return b;
    }

    private ResponseEntity<Map> capture(String token, String orderId, Map<String, Object> body, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        try {
            return exchange("/v1/nursing/procedure-orders/" + orderId + "/consent", HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), h), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<Map> status(Clinic c, String orderId, String status) {
        return exchange("/v1/nursing/procedure-orders/" + orderId + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", status)), Map.class);
    }

    private long consents(Clinic c, String orderId) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM procedure_consents WHERE procedure_order_id = ?::uuid", Long.class, orderId));
    }

    @Test
    void e01_theFormIsBoundToItsProcedureClinicAndGrant() {
        Clinic c = clinic();
        template(c, "PEEL");
        Map<String, Object> laser = template(c, "LASER");
        String ord = order(c, patient(c, "1990-01-01", null), "PEEL");
        ResponseEntity<Map> wrongForm = capture(c.token(), ord, body(laser, "patient", null, c.staff().id(), SIG), UUID.randomUUID().toString());
        assertThat(wrongForm.getStatusCode()).as("LASER form on a PEEL order").isEqualTo(HttpStatus.BAD_REQUEST);

        Clinic other = clinic();
        Map<String, Object> otherForm = template(other, "PEEL");
        ResponseEntity<Map> foreign = capture(other.token(), ord, body(otherForm, "patient", null, other.staff().id(), SIG), UUID.randomUUID().toString());
        assertThat(foreign.getStatusCode()).as("another clinic's order").isEqualTo(HttpStatus.NOT_FOUND);

        UUID viewOnly = seedRole(c.tenant().id(), "NurseView", false, new ModuleGrant("nursing", true, false, false, false, false, false, false));
        String v = loginAndGetAccessToken(seedStaff(c.tenant(), viewOnly, "qa22-nv-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        Map<String, Object> peel = (Map<String, Object>) ((List) exchange("/v1/nursing/consent-templates?chargeCode=PEEL&lang=en", HttpMethod.GET, authed(c.token()), Map.class)
                .getBody().get("items")).get(0);
        assertThat(capture(v, ord, body(peel, "patient", null, c.staff().id(), SIG), UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(consents(c, ord)).isZero();
    }

    @Test
    void e02_aReplayedCaptureGivesOneConsent_andAfterWithdrawalNothingStartsAndNoNewConsentIsTaken() {
        Clinic c = clinic();
        Map<String, Object> peel = template(c, "PEEL");
        String ord = order(c, patient(c, "1985-05-05", null), "PEEL");
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = capture(c.token(), ord, body(peel, "patient", null, c.staff().id(), SIG), key);
        ResponseEntity<Map> again = capture(c.token(), ord, body(peel, "patient", null, c.staff().id(), SIG), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("consentId")).isEqualTo(first.getBody().get("consentId"));
        assertThat(consents(c, ord)).isEqualTo(1);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'nursing.procedure_consent_signed' AND entity_id = ?::uuid",
                Long.class, ord))).isEqualTo(1);

        assertThat(exchange("/v1/nursing/procedure-orders/" + ord + "/consent/withdraw", HttpMethod.POST, authedJsonBody(c.token(), Map.of("reason", "Changed mind")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String s : List.of("prepped", "completed")) {
            ResponseEntity<Map> r = status(c, ord, s);
            assertThat(r.getStatusCode()).as(s).isEqualTo(HttpStatus.CONFLICT);
            assertThat(String.valueOf(r.getBody().get("type"))).contains("CONSENT_REQUIRED");
        }
        ResponseEntity<Map> retake = capture(c.token(), ord, body(peel, "patient", null, c.staff().id(), SIG), UUID.randomUUID().toString());
        assertThat(retake.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(retake.getBody().get("type"))).contains("CONSENT_WITHDRAWN");
        assertThat(capture(c.token(), ord, Map.of("signedName", "Typed Name"), UUID.randomUUID().toString()).getStatusCode()).as("typed name after withdrawal")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void e03_aMinorNeedsTheirOwnLinkedGuardian_assentIsOptional() {
        Clinic c = clinic();
        Map<String, Object> peel = template(c, "PEEL");
        String g1 = patient(c, "1980-01-01", null);
        String g2 = patient(c, "1981-01-01", null);
        String minorDob = LocalDate.now().minusYears(15).toString();
        String minor = patient(c, minorDob, g1);
        patient(c, LocalDate.now().minusYears(14).toString(), g2); // g2 is a guardian, but of someone else
        String ord = order(c, minor, "PEEL");
        assertThat(capture(c.token(), ord, body(peel, "guardian", g2, c.staff().id(), SIG), UUID.randomUUID().toString()).getStatusCode())
                .as("another patient's guardian").isEqualTo(HttpStatus.CONFLICT);
        assertThat(capture(c.token(), ord, body(peel, "patient", null, c.staff().id(), SIG), UUID.randomUUID().toString()).getStatusCode())
                .as("the minor alone").isEqualTo(HttpStatus.CONFLICT);
        assertThat(capture(c.token(), ord, body(peel, "guardian", minor, c.staff().id(), SIG), UUID.randomUUID().toString()).getStatusCode())
                .as("the minor named as their own guardian").isEqualTo(HttpStatus.CONFLICT);
        assertThat(consents(c, ord)).isZero();
        ResponseEntity<Map> ok = capture(c.token(), ord, body(peel, "guardian", g1, c.staff().id(), SIG), UUID.randomUUID().toString());
        assertThat(ok.getStatusCode()).as("linked guardian, no assent: %s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void e05_round2_theOrderTellsTheDialogTheMinorAndGuardian_andGuardianIdMayBeOmittedOnlyWhenOneIsLinked() {
        Clinic c = clinic();
        Map<String, Object> peel = template(c, "PEEL");
        String g = patient(c, "1979-04-04", null);
        String minor = patient(c, LocalDate.now().minusYears(15).toString(), g);
        // a minor cannot be registered without a guardian (400), so: registered as an adult, date of birth corrected afterwards
        String alone = patient(c, "1990-06-06", null);
        inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE patients SET dob = ? WHERE id = ?::uuid", LocalDate.now().minusYears(16), alone));
        String withGuardian = order(c, minor, "PEEL");
        String noGuardian = order(c, alone, "PEEL");
        List<Map<String, Object>> today = exchange("/v1/nursing/procedure-orders/today", HttpMethod.GET, authed(c.token()), List.class).getBody();
        Map<String, Object> row = today.stream().filter(o -> withGuardian.equals(o.get("id"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("patientMinor", true).containsKey("guardianName");
        assertThat(row.get("guardianName")).isNotNull();
        assertThat(capture(c.token(), noGuardian, body(peel, "guardian", null, c.staff().id(), SIG), UUID.randomUUID().toString()).getStatusCode())
                .as("no linked guardian: still refused").isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> ok = capture(c.token(), withGuardian, body(peel, "guardian", null, c.staff().id(), SIG), UUID.randomUUID().toString());
        assertThat(ok.getStatusCode()).as("guardianId omitted, linked guardian used: %s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT guardian_id::text FROM procedure_consents WHERE procedure_order_id = ?::uuid",
                String.class, withGuardian))).isEqualTo(g);
    }

    @Test
    void e04_signatureDataAtTheTrustBoundary() {
        Clinic c = clinic();
        Map<String, Object> peel = template(c, "PEEL");
        String a = order(c, patient(c, "1970-02-02", null), "PEEL");
        for (String svg : new String[]{null, "", "<svg></svg>", "<svg><path d=\"\"/></svg>"}) {
            ResponseEntity<Map> r = capture(c.token(), a, body(peel, "patient", null, c.staff().id(), svg), UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("svg " + svg).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(capture(c.token(), a, body(peel, "patient", null, c.staff().id(), "<svg><path d=\"M10 10 C 20 20 30 30 40 40\"/></svg>"), UUID.randomUUID().toString())
                .getStatusCode()).as("curve commands").isEqualTo(HttpStatus.BAD_REQUEST);

        String withScript = "<svg onload=\"alert(1)\"><script>alert(2)</script><path d=\"M10 10 L30 20 L50 35 L70 30 L90 50 L110 45 L130 60 L150 55 L170 70 L190 65 L210 80\" onclick=\"x()\"/></svg>";
        ResponseEntity<Map> ok = capture(c.token(), a, body(peel, "patient", null, c.staff().id(), withScript), UUID.randomUUID().toString());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String storedSvg = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT signature_file_id::text FROM procedure_consents WHERE procedure_order_id = ?::uuid",
                String.class, a));
        assertThat(storedSvg).isNotNull();

        String b = order(c, patient(c, "1971-03-03", null), "PEEL");
        // round 2 (N-2 fix): too little ink is not a signature: a dot, a 3-point tap, 11 points within 10 units
        for (String svg : new String[]{"<svg><path d=\"M0 0\"/></svg>", "<svg><path d=\"M100 100 L101 101 L102 100\"/></svg>",
                "<svg><path d=\"M100 100 L101 100 L102 100 L103 100 L104 100 L105 100 L106 100 L107 100 L108 100 L109 100 L110 100\"/></svg>"}) {
            ResponseEntity<Map> r = capture(c.token(), b, body(peel, "patient", null, c.staff().id(), svg), UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("too little ink " + svg).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(r.getBody().get("type"))).contains("SIGNATURE_REQUIRED");
        }
        assertThat(consents(c, b)).isZero();
        assertThat(capture(c.token(), b, body(peel, "patient", null, c.staff().id(), SIG), UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
