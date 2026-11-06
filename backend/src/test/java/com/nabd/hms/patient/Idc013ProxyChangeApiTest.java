package com.nabd.hms.patient;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-013: the patient downgrades or removes a caregiver (or the clinic does on a verified request) and it applies on the next request. */
class Idc013ProxyChangeApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired PatientRefs refs;

    private record Clinic(SeededTenant t, String edge, UUID caregiver, String caregiverRef, UUID subject, String subjectRef, UUID proxy, SeededStaff reception) {
        String base() {
            return "/internal/v1/tenants/" + t.id();
        }
    }

    private static String phone() {
        return "+9197" + (10000000 + (int) (Math.random() * 89999999));
    }

    /** A caregiver (Divya) with an accepted FULL proxy for her mother (Lakshmi), the way IDC-009 leaves it after the OTP link. */
    private Clinic clinic() {
        SeededTenant t = seedTenant();
        UUID caregiver = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Divya Pillai', ?, '1985-05-05', 'female') RETURNING id", UUID.class, t.id(), phone()));
        UUID subject = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Lakshmi Pillai', ?, '1958-03-04', 'female') RETURNING id", UUID.class, t.id(), phone()));
        UUID proxy = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) "
                + "VALUES (?,?,?,'daughter','FULL','active',now()) RETURNING id", UUID.class, t.id(), caregiver, subject));
        SeededStaff reception = seedStaff(t, seedRole(t.id(), "Reception", false, new ModuleGrant("patients", true, true, true, false, false, false, false)),
                "rec" + t.slug() + "@a.com", phone(), false);
        return new Clinic(t, InternalTestTokens.mint(encoder, "patient-access-edge", t.id()), caregiver, inTenantTx(t.id(), () -> refs.refFor(caregiver)),
                subject, inTenantTx(t.id(), () -> refs.refFor(subject)), proxy, reception);
    }

    private ResponseEntity<Map> change(Clinic c, UUID proxy, String subjectRef, String scope, Boolean revoke) {
        Map<String, Object> m = new HashMap<>();
        m.put("subjectRef", subjectRef);
        m.put("scope", scope);
        m.put("revoke", revoke);
        return exchange(c.base() + "/family/proxies/" + proxy, HttpMethod.PATCH, authedJsonBody(c.edge(), m), Map.class);
    }

    private ResponseEntity<Map> access(Clinic c, String need) {
        return exchange(c.base() + "/family/members/" + c.subjectRef() + "/access?caregiverRef=" + c.caregiverRef() + "&need=" + need, HttpMethod.GET, authed(c.edge()), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members(Clinic c) {
        return (List<Map<String, Object>>) exchange(c.base() + "/family/members?caregiverRef=" + c.caregiverRef(), HttpMethod.GET, authed(c.edge()), Map.class).getBody().get("members");
    }

    private List<Map<String, Object>> events(Clinic c) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForList("SELECT envelope->'data'->>'scope' AS scope, envelope->'data'->>'status' AS status, envelope->'data'->>'proxyRef' AS proxy_ref, "
                + "envelope->'data'->>'subjectRef' AS subject_ref, envelope->>'subject' AS subject FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'patient.proxy.changed.v1' ORDER BY (envelope->>'id')::bigint", c.t().id()));
    }

    private String stepUp(String access) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId"));
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> clinicRevoke(Clinic c, String token, UUID patient, UUID proxy, boolean withStepUp, String reason) throws Exception {
        return exchange("/v1/patients/" + patient + "/proxies/" + proxy + "/revoke", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "X-Step-Up-Token", withStepUp ? stepUp(token) : null, Map.of("reason", reason)), Map.class);
    }

    private String proxyRow(Clinic c) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT status || '/' || coalesce(scope, '-') || '/' || coalesce(revoked_by, '-') FROM patient_proxies WHERE id = ?", String.class, c.proxy()));
    }

    @Test
    void ac1_downgradeToBookOnly_theNextRecordReadIs403_andTheEventSaysSo() {
        Clinic c = clinic();
        assertThat(access(c, "records").getStatusCode()).as("FULL before").isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mine = (List<Map<String, Object>>) exchange(c.base() + "/family/proxies?subjectRef=" + c.subjectRef(), HttpMethod.GET, authed(c.edge()), Map.class).getBody().get("proxies");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0)).containsEntry("id", c.proxy().toString()).containsEntry("caregiverFirstName", "Divya").containsEntry("relation", "daughter").containsEntry("scope", "FULL");

        ResponseEntity<Map> r = change(c, c.proxy(), c.subjectRef(), "BOOK_ONLY", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "active").containsEntry("scope", "BOOK_ONLY");

        ResponseEntity<Map> records = access(c, "records"); // evaluated on this request: no cache to wait out
        assertThat(records.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(records.getBody()).containsEntry("code", "proxy-scope-insufficient");
        assertThat(access(c, "book").getStatusCode()).as("booking stays open").isEqualTo(HttpStatus.OK);
        assertThat(members(c)).hasSize(1);

        List<Map<String, Object>> ev = events(c);
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0)).containsEntry("scope", "BOOK_ONLY").containsEntry("status", "active").containsEntry("proxy_ref", c.caregiverRef())
                .containsEntry("subject_ref", c.subjectRef()).containsEntry("subject", c.proxy().toString());
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'patient.proxy_scope_changed' AND entity_id = ? AND after->>'scope' = 'BOOK_ONLY'", Integer.class, c.proxy()))).isEqualTo(1);

        assertThat(change(c, c.proxy(), c.subjectRef(), "EVERYTHING", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(change(c, c.proxy(), c.subjectRef(), null, null).getStatusCode()).as("neither scope nor revoke").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac2_revoke_theMemberLeavesThePickerAndEveryGateCloses_idempotent_andOnlyForTheOwnProxy() {
        Clinic c = clinic();
        Clinic other = clinic();
        assertThat(change(c, c.proxy(), other.subjectRef(), null, true).getStatusCode()).as("someone else's proxy id: same answer as unknown").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(change(c, UUID.randomUUID(), c.subjectRef(), null, true).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(proxyRow(c)).isEqualTo("active/FULL/-");

        ResponseEntity<Map> r = change(c, c.proxy(), c.subjectRef(), null, true);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "revoked");
        assertThat(proxyRow(c)).isEqualTo("revoked/FULL/patient");

        assertThat(members(c)).as("gone from the caregiver's picker").isEmpty();
        assertThat(access(c, "book").getBody()).containsEntry("code", "not-a-proxy");
        assertThat(access(c, "records").getBody()).containsEntry("code", "not-a-proxy");
        List<Map<String, Object>> ev = events(c);
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0)).containsEntry("status", "revoked").containsEntry("proxy_ref", c.caregiverRef());

        assertThat(change(c, c.proxy(), c.subjectRef(), null, true).getStatusCode()).as("revoke again: same answer").isEqualTo(HttpStatus.OK);
        assertThat(change(c, c.proxy(), c.subjectRef(), "FULL", null).getStatusCode()).as("no scope on a revoked proxy").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(events(c)).as("nothing new published").hasSize(1);
    }

    @Test
    void ac3_clinicRevokeNeedsStepUpAndPatientsEdit_andTheReasonIsAudited() throws Exception {
        Clinic c = clinic();
        String reception = loginAndGetAccessToken(c.reception());
        ResponseEntity<Map> noStepUp = clinicRevoke(c, reception, c.subject(), c.proxy(), false, "Patient asked at the desk");
        assertThat(noStepUp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noStepUp.getBody()).containsEntry("code", "STEP_UP_REQUIRED");
        assertThat(proxyRow(c)).isEqualTo("active/FULL/-");

        SeededStaff viewer = seedStaff(c.t(), seedRole(c.t().id(), "Viewer", false, new ModuleGrant("patients", true, false, false, false, false, false, false)), "view" + c.t().slug() + "@a.com", phone(), false);
        assertThat(clinicRevoke(c, loginAndGetAccessToken(viewer), c.subject(), c.proxy(), true, "x").getStatusCode()).as("patients:edit required").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(clinicRevoke(c, reception, c.caregiver(), c.proxy(), true, "x").getStatusCode()).as("proxy id under the wrong patient").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(clinicRevoke(c, reception, c.subject(), c.proxy(), true, " ").getStatusCode()).as("reason required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(proxyRow(c)).isEqualTo("active/FULL/-");

        assertThat(clinicRevoke(c, reception, c.subject(), c.proxy(), true, "Patient asked at the desk").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(proxyRow(c)).isEqualTo("revoked/FULL/clinic");
        Map<String, Object> audit = inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT actor_type, actor_id, after->>'reason' AS reason, after->>'revokedBy' AS by FROM audit_log WHERE action = 'patient.proxy_revoked' AND entity_id = ?", c.proxy()));
        assertThat(audit).containsEntry("actor_type", "staff").containsEntry("actor_id", c.reception().id()).containsEntry("reason", "Patient asked at the desk").containsEntry("by", "clinic");
        assertThat(members(c)).isEmpty();
        assertThat(clinicRevoke(c, reception, c.subject(), c.proxy(), true, "again").getStatusCode()).as("idempotent").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(events(c)).hasSize(1);
    }

    @Test
    void ac4_anAppointmentTheCaregiverBookedStaysValid_andThePatientIsToldWhoBookedIt() {
        Clinic c = clinic();
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID apt = inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, acted_by_patient_id, acted_by_relation) "
                + "VALUES (?,?,?,?,?,?,'daughter') RETURNING id", UUID.class, c.t().id(), c.subject(), c.reception().id(), java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(15, ChronoUnit.MINUTES)), c.caregiver()));
        assertThat(change(c, c.proxy(), c.subjectRef(), null, true).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, apt))).isEqualTo("scheduled");
        ResponseEntity<Map> detail = exchange(c.base() + "/appointments/" + apt + "?patientRef=" + c.subjectRef(), HttpMethod.GET, authed(c.edge()), Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsEntry("status", "scheduled").containsEntry("bookedByRelation", "daughter");
        // the removed caregiver no longer acts for the patient on that appointment
        ResponseEntity<Map> cancel = exchange(c.base() + "/appointments/" + apt + "/cancel", HttpMethod.POST,
                authedJsonBody(c.edge(), Map.of("patientRef", c.subjectRef(), "reasonCode", "patient_request", "actedByRef", c.caregiverRef())), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel.getBody()).containsEntry("code", "not-a-proxy");
    }
}
