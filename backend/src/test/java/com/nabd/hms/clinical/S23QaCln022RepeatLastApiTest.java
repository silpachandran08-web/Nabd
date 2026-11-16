package com.nabd.hms.clinical;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S23 QA (t1) CLN-022 x CLN-023: repeat-last must not bring back a prescription the doctor revoked; the Idempotency-Key is bound to
 * the request body; the endpoint is closed to non-prescribers and other clinics. Drug names are synthetic labels, not clinical content.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S23QaCln022RepeatLastApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 300_000);

    private record Clinic(SeededTenant t, SeededStaff doctor, String token) {
    }

    private static String phone() {
        return "+9192" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff doc = ClinicianFixture.licensed(seedStaff(t, seedFullAccessRole(t.id()), "q22" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        return new Clinic(t, doc, loginAndGetAccessToken(doc));
    }

    private String patient(Clinic c) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Rep " + SEQ.incrementAndGet(), "phone", phone(),
                "dob", "1960-02-02", "gender", "male")), Map.class).getBody().get("id");
    }

    private String checkIn(Clinic c, String patientId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.doctor().id().toString())), Map.class).getBody().get("id");
    }

    private String signRx(Clinic c, String entry, String drug, String dose) {
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", List.of(
                Map.of("drugName", drug, "dosage", dose, "frequency", "OD")))), Map.class);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) saved.getBody().get("id");
    }

    private ResponseEntity<Map> repeat(String token, String entry, Object body) {
        return exchange("/v1/clinical/prescriptions/" + entry + "/repeat-last", HttpMethod.POST,
                body == null ? authed(token) : authedJsonBody(token, body), Map.class);
    }

    private static List<Map> items(ResponseEntity<Map> rx) {
        return rx.getBody() == null ? List.of() : (List<Map>) rx.getBody().get("items");
    }

    // C22-A01: the last Rx was revoked (dose entered wrongly): Repeat last must not bring the wrong dose back
    @Test
    void c22a01_repeatLastSkipsARevokedPrescription() {
        Clinic c = clinic();
        String p = patient(c);
        String v1 = checkIn(c, p);
        signRx(c, v1, "QaDrug Good", "10 mg");
        String v2 = checkIn(c, p);
        signRx(c, v2, "QaDrug Wrong", "1000 mg");
        ResponseEntity<Map> rv = exchange("/v1/clinical/prescriptions/" + v2 + "/revoke", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "Dose entered wrongly")), Map.class);
        assertThat(rv.getStatusCode()).as("%s", rv.getBody()).isEqualTo(HttpStatus.OK);

        String v3 = checkIn(c, p);
        ResponseEntity<Map> r = repeat(c.token(), v3, null);
        String repeated = items(r).isEmpty() ? null : items(r).get(0).get("drugName") + " " + items(r).get(0).get("dosage");
        System.out.println("C22-A01 repeat-last after revoke=" + r.getStatusCode() + " lines=" + repeated);
        assertThat(repeated).as("the revoked 'QaDrug Wrong 1000 mg' must not be repeated").isNotEqualTo("QaDrug Wrong 1000 mg");
    }

    // C22-A02: pinning the revoked Rx explicitly is refused too
    @Test
    void c22a02_repeatLastOfAnExplicitlyPinnedRevokedRxIsRefused() {
        Clinic c = clinic();
        String p = patient(c);
        String v1 = checkIn(c, p);
        String revokedRx = signRx(c, v1, "QaDrug Pinned", "500 mg");
        assertThat(exchange("/v1/clinical/prescriptions/" + v1 + "/revoke", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "Wrong patient selected")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String v2 = checkIn(c, p);
        ResponseEntity<Map> r = repeat(c.token(), v2, Map.of("sourcePrescriptionId", revokedRx));
        System.out.println("C22-A02 pinned revoked=" + r.getStatusCode() + " items=" + items(r).size());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("a revoked Rx must not be repeatable").isFalse();
    }

    // C22-A03: the same Idempotency-Key with a different body is not a replay
    @Test
    void c22a03_theSameKeyWithAnotherSourceIs422AndRunsOnce() {
        Clinic c = clinic();
        String p = patient(c);
        String v1 = checkIn(c, p);
        String older = signRx(c, v1, "QaDrug Older", "5 mg");
        String v2 = checkIn(c, p);
        signRx(c, v2, "QaDrug Newer", "7 mg");
        String v3 = checkIn(c, p);
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> a = exchange("/v1/clinical/prescriptions/" + v3 + "/repeat-last", HttpMethod.POST,
                authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, Map.of()), Map.class);
        ResponseEntity<Map> b = exchange("/v1/clinical/prescriptions/" + v3 + "/repeat-last", HttpMethod.POST,
                authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, Map.of("sourcePrescriptionId", older)), Map.class);
        System.out.println("C22-A03 first=" + a.getStatusCode() + " reused key, other body=" + b.getStatusCode() + " " + b.getBody());
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Integer audited = inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'prescription.repeated_last'", Integer.class, c.t().id()));
        assertThat(audited).isEqualTo(1);
    }

    // C22-A04: a desk user without clinical:edit gets 403; another clinic's doctor gets 404
    @Test
    void c22a04_receptionist403AndOtherClinic404() {
        Clinic c = clinic();
        String p = patient(c);
        signRx(c, checkIn(c, p), "QaDrug Desk", "5 mg");
        String v2 = checkIn(c, p);
        UUID deskRole = seedRole(c.t().id(), "Desk " + SEQ.incrementAndGet(), false, fullGrant("queue"), fullGrant("patients"), fullGrant("billing"));
        String desk = loginAndGetAccessToken(seedStaff(c.t(), deskRole, "desk" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        Clinic other = clinic();
        ResponseEntity<Map> d = repeat(desk, v2, null);
        ResponseEntity<Map> o = repeat(other.token(), v2, null);
        System.out.println("C22-A04 desk=" + d.getStatusCode() + " other clinic=" + o.getStatusCode());
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(o.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
