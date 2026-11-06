package com.nabd.hms.patient;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/** S20 QA (independent): IDC-013 edges: scope steps, tenant isolation, step-up binding and reuse, pending proxies, input bounds. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S20QaIdc013ProxyChangeApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired PatientRefs refs;

    private record Clinic(SeededTenant t, String edge, UUID caregiver, String caregiverRef, UUID subject, String subjectRef, UUID proxy, SeededStaff reception) {
        String base() { return "/internal/v1/tenants/" + t.id(); }
    }

    private static String phone() { return "+9197" + (10000000 + (int) (Math.random() * 89999999)); }

    private Clinic clinic() { return clinic("active", "FULL"); }

    private Clinic clinic(String status, String scope) {
        SeededTenant t = seedTenant();
        UUID caregiver = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Divya Pillai', ?, '1985-05-05', 'female') RETURNING id", UUID.class, t.id(), phone()));
        UUID subject = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Lakshmi Pillai', ?, '1958-03-04', 'female') RETURNING id", UUID.class, t.id(), phone()));
        UUID proxy = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) "
                + "VALUES (?,?,?,'daughter',?,?, CASE WHEN ? = 'active' THEN now() END) RETURNING id", UUID.class, t.id(), caregiver, subject, scope, status, status));
        SeededStaff reception = seedStaff(t, seedRole(t.id(), "Reception", false, new ModuleGrant("patients", true, true, true, false, false, false, false)),
                "rec" + t.slug() + "@a.com", phone(), false);
        return new Clinic(t, InternalTestTokens.mint(encoder, "patient-access-edge", t.id()), caregiver, inTenantTx(t.id(), () -> refs.refFor(caregiver)),
                subject, inTenantTx(t.id(), () -> refs.refFor(subject)), proxy, reception);
    }

    private UUID secondProxy(Clinic c) {
        UUID other = inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Ravi Pillai', ?, '1988-01-01', 'male') RETURNING id", UUID.class, c.t().id(), phone()));
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) "
                + "VALUES (?,?,?,'son','FULL','active',now()) RETURNING id", UUID.class, c.t().id(), other, c.subject()));
    }

    private ResponseEntity<Map> change(String edge, UUID tenant, UUID proxy, String subjectRef, String scope, Boolean revoke) {
        Map<String, Object> m = new HashMap<>();
        m.put("subjectRef", subjectRef);
        m.put("scope", scope);
        m.put("revoke", revoke);
        return exchange("/internal/v1/tenants/" + tenant + "/family/proxies/" + proxy, HttpMethod.PATCH, authedJsonBody(edge, m), Map.class);
    }

    private ResponseEntity<Map> access(Clinic c, String need) {
        return exchange(c.base() + "/family/members/" + c.subjectRef() + "/access?caregiverRef=" + c.caregiverRef() + "&need=" + need, HttpMethod.GET, authed(c.edge()), Map.class);
    }

    private List<Map<String, Object>> members(Clinic c) {
        return (List<Map<String, Object>>) exchange(c.base() + "/family/members?caregiverRef=" + c.caregiverRef(), HttpMethod.GET, authed(c.edge()), Map.class).getBody().get("members");
    }

    private int events(Clinic c, String status) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'patient.proxy.changed.v1' AND envelope->'data'->>'status' = ?", Integer.class, c.t().id(), status));
    }

    private String stepUp(String access, String purpose, long ttlSeconds, String subjectOverride) throws Exception {
        return stepUp(access, purpose, ttlSeconds, subjectOverride, null, true);
    }

    /** Fix round (T20-BUG-2): a step-up token now carries a jti and the target it was minted for (the proxy id). */
    private String stepUp(String access, String purpose, long ttlSeconds, String subjectOverride, String target, boolean withJti) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now.minusSeconds(ttlSeconds < 0 ? -ttlSeconds + 1 : 0)).expiresAt(now.plusSeconds(ttlSeconds))
                .subject(subjectOverride != null ? subjectOverride : (String) c.get("sub")).claim("purpose", purpose).claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId"));
        if (withJti) b.id(UUID.randomUUID().toString());
        if (target != null) b.claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private String stepUpFor(String access, UUID proxy) throws Exception { return stepUp(access, "step_up", 300, null, proxy.toString(), true); }

    private ResponseEntity<Map> clinicRevoke(String token, UUID patient, UUID proxy, String stepUpToken, String reason) {
        return exchange("/v1/patients/" + patient + "/proxies/" + proxy + "/revoke", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUpToken, Map.of("reason", reason)), Map.class);
    }

    private String row(Clinic c, UUID proxy) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT status || '/' || coalesce(scope, '-') || '/' || coalesce(revoked_by, '-') FROM patient_proxies WHERE id = ?", String.class, proxy));
    }

    private int audits(Clinic c, UUID proxy, String action) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, proxy));
    }

    @Test
    void iApi01_scopeStepsApplyOnTheNextRequest_recordsThenBookOnly() {
        Clinic c = clinic();
        assertThat(access(c, "records").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(change(c.edge(), c.t().id(), c.proxy(), c.subjectRef(), "BOOK_AND_RECORDS", null).getBody()).containsEntry("scope", "BOOK_AND_RECORDS");
        assertThat(access(c, "records").getStatusCode()).as("records still inside BOOK_AND_RECORDS").isEqualTo(HttpStatus.OK);
        assertThat(change(c.edge(), c.t().id(), c.proxy(), c.subjectRef(), "BOOK_ONLY", null).getBody()).containsEntry("scope", "BOOK_ONLY");
        ResponseEntity<Map> rec = access(c, "records");
        assertThat(rec.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rec.getBody()).containsEntry("code", "proxy-scope-insufficient");
        assertThat(access(c, "book").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits(c, c.proxy(), "patient.proxy_scope_changed")).isEqualTo(2);
        // back up to FULL is allowed by the patient (an upgrade is the patient's own decision on the verified phone)
        assertThat(change(c.edge(), c.t().id(), c.proxy(), c.subjectRef(), "FULL", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(access(c, "records").getStatusCode()).isEqualTo(HttpStatus.OK);
        // a scope change with revoke=false in the same body is just the scope change
        assertThat(change(c.edge(), c.t().id(), c.proxy(), c.subjectRef(), "BOOK_ONLY", false).getBody()).containsEntry("status", "active").containsEntry("scope", "BOOK_ONLY");
        assertThat(row(c, c.proxy())).isEqualTo("active/BOOK_ONLY/-");
    }

    @Test
    void iApi02_03_revokeClosesEveryGate_noResurrection_andAnotherTenantsEdgeSeesNothing() {
        Clinic a = clinic();
        Clinic b = clinic();
        // tenant B's Edge token against A's proxy: 404 both ways, A untouched
        assertThat(change(b.edge(), b.t().id(), a.proxy(), a.subjectRef(), null, true).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(change(b.edge(), a.t().id(), a.proxy(), a.subjectRef(), null, true).getStatusCode()).as("B's token on A's tenant path").isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED);
        assertThat(row(a, a.proxy())).isEqualTo("active/FULL/-");

        assertThat(change(a.edge(), a.t().id(), a.proxy(), a.subjectRef(), null, true).getBody()).containsEntry("status", "revoked");
        assertThat(members(a)).isEmpty();
        assertThat(access(a, "book").getBody()).containsEntry("code", "not-a-proxy");
        assertThat(access(a, "records").getBody()).containsEntry("code", "not-a-proxy");
        assertThat(change(a.edge(), a.t().id(), a.proxy(), a.subjectRef(), "FULL", null).getStatusCode()).as("no resurrection").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(change(a.edge(), a.t().id(), a.proxy(), a.subjectRef(), "FULL", true).getStatusCode()).as("scope + revoke on a revoked proxy").isEqualTo(HttpStatus.OK);
        assertThat(row(a, a.proxy())).isEqualTo("revoked/FULL/patient");
        assertThat(events(a, "revoked")).isEqualTo(1);
        assertThat(inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'patient.proxy.changed.v1' AND envelope::text ILIKE '%Pillai%'", Integer.class, a.t().id())))
                .as("no names in the event").isZero();
    }

    @Test
    void iApi04_05_06_stepUpIsRequired_boundToTheStaffAndSession_andPatientsEditIsNeeded() throws Exception {
        Clinic c = clinic();
        String rec = loginAndGetAccessToken(c.reception());
        ResponseEntity<Map> none = clinicRevoke(rec, c.subject(), c.proxy(), null, "Patient asked");
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(none.getBody()).containsEntry("code", "STEP_UP_REQUIRED");
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), "", "Patient asked").getStatusCode()).as("blank header").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), "not.a.jwt", "Patient asked").getStatusCode()).as("garbage header").isEqualTo(HttpStatus.FORBIDDEN);

        // another staff member's step-up (sub mismatch)
        SeededStaff other = seedStaff(c.t(), c.reception().roleId(), "o" + c.t().slug() + "@a.com", phone(), false);
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), stepUp(rec, "step_up", 300, other.id().toString(), c.proxy().toString(), true), "x").getStatusCode()).as("sub mismatch").isEqualTo(HttpStatus.FORBIDDEN);
        // the same staff member's step-up from another session (sid mismatch)
        String rec2 = loginAndGetAccessToken(c.reception());
        ResponseEntity<Map> sid = clinicRevoke(rec, c.subject(), c.proxy(), stepUpFor(rec2, c.proxy()), "x");
        assertThat(sid.getStatusCode()).as("sid mismatch").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(sid.getBody())).contains("STEP_UP");
        // wrong purpose and expired
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), stepUp(rec, "pin_step_up", 300, null, c.proxy().toString(), true), "x").getStatusCode()).as("wrong purpose").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), stepUp(rec, "step_up", -60, null, c.proxy().toString(), true), "x").getStatusCode()).as("expired").isEqualTo(HttpStatus.FORBIDDEN);
        // patients:view only, with a perfectly good step-up
        SeededStaff viewer = seedStaff(c.t(), seedRole(c.t().id(), "Viewer", false, new ModuleGrant("patients", true, false, false, false, false, false, false)), "v" + c.t().slug() + "@a.com", phone(), false);
        String view = loginAndGetAccessToken(viewer);
        assertThat(clinicRevoke(view, c.subject(), c.proxy(), stepUpFor(view, c.proxy()), "x").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(row(c, c.proxy())).isEqualTo("active/FULL/-");
        assertThat(audits(c, c.proxy(), "patient.proxy_revoked")).isZero();
        assertThat(events(c, "revoked")).isZero();
    }

    @Test
    void iApi07_08_clinicRevokeIsAudited_andTheStepUpTokenCanBeReusedOnASecondProxy() throws Exception {
        Clinic c = clinic();
        UUID second = secondProxy(c);
        String rec = loginAndGetAccessToken(c.reception());
        // fix round (T20-BUG-2): no target, no jti, or a target naming the other proxy -> refused
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), stepUp(rec, "step_up", 300, null, null, true), "x").getStatusCode()).as("no target").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), stepUp(rec, "step_up", 300, null, c.proxy().toString(), false), "x").getStatusCode()).as("no jti").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), stepUpFor(rec, second), "x").getStatusCode()).as("token for the other proxy").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(row(c, c.proxy())).isEqualTo("active/FULL/-");
        String token = stepUpFor(rec, c.proxy());
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), token, "Patient asked at the desk").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(row(c, c.proxy())).isEqualTo("revoked/FULL/clinic");
        Map<String, Object> audit = inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT actor_type, actor_id, after->>'reason' AS reason, after->>'revokedBy' AS by, before->>'scope' AS before_scope FROM audit_log WHERE action = 'patient.proxy_revoked' AND entity_id = ?", c.proxy()));
        assertThat(audit).containsEntry("actor_type", "staff").containsEntry("actor_id", c.reception().id()).containsEntry("reason", "Patient asked at the desk").containsEntry("by", "clinic").containsEntry("before_scope", "FULL");
        assertThat(members(c)).isEmpty();
        assertThat(access(c, "book").getBody()).containsEntry("code", "not-a-proxy");
        assertThat(events(c, "revoked")).isEqualTo(1);

        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT after->>'stepUpId' IS NOT NULL FROM audit_log WHERE action = 'patient.proxy_revoked' AND entity_id = ?", Boolean.class, c.proxy()))).as("jti audited").isTrue();
        // T20-BUG-2 retest: the consumed token is refused on the second proxy (wrong target) and on its own proxy again is idempotent 204
        ResponseEntity<Map> reuse = clinicRevoke(rec, c.subject(), second, token, "Second one");
        assertThat(reuse.getStatusCode()).as("step-up reused on another proxy").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(row(c, second)).isEqualTo("active/FULL/-");
        // a forged token with the consumed jti but the second proxy as target: still refused (jti single-use)
        Map<String, Object> claims = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder forged = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300)).id((String) claims.get("jti"))
                .subject((String) claims.get("sub")).claim("purpose", "step_up").claim("sid", claims.get("sid")).claim("tenantId", claims.get("tenantId")).claim("target", second.toString());
        TokenType.STEP_UP.stamp(forged, Audience.STEP_UP);
        assertThat(clinicRevoke(rec, c.subject(), second, encoder.encode(JwtEncoderParameters.from(forged.build())).getTokenValue(), "replay").getStatusCode()).as("consumed jti replayed with a new target").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(row(c, second)).isEqualTo("active/FULL/-");
        assertThat(clinicRevoke(rec, c.subject(), second, stepUpFor(rec, second), "Second one").getStatusCode()).as("a fresh token for the second proxy").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(row(c, second)).isEqualTo("revoked/FULL/clinic");
    }

    @Test
    void iApi09_10_inputBounds_wrongPatient_andAnotherTenantsStaff() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        String rec = loginAndGetAccessToken(a.reception());
        assertThat(clinicRevoke(rec, a.subject(), a.proxy(), stepUpFor(rec, a.proxy()), " ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(clinicRevoke(rec, a.subject(), a.proxy(), stepUpFor(rec, a.proxy()), "r".repeat(201)).getStatusCode()).as("reason > 200").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(clinicRevoke(rec, a.caregiver(), a.proxy(), stepUpFor(rec, a.proxy()), "x").getStatusCode()).as("proxy not under that patient").isEqualTo(HttpStatus.NOT_FOUND);
        UUID ghost = UUID.randomUUID();
        assertThat(clinicRevoke(rec, a.subject(), ghost, stepUpFor(rec, ghost), "x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(clinicRevoke(rec, a.subject(), ghost, stepUpFor(rec, a.proxy()), "x").getStatusCode()).as("target mismatch is checked first").isEqualTo(HttpStatus.FORBIDDEN);
        // no Idempotency-Key on an @Idempotent endpoint
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(rec);
        h.set("X-Step-Up-Token", stepUpFor(rec, a.proxy()));
        ResponseEntity<Map> noKey = exchange("/v1/patients/" + a.subject() + "/proxies/" + a.proxy() + "/revoke", HttpMethod.POST, new HttpEntity<>("{\"reason\":\"x\"}", h), Map.class);
        assertThat(noKey.getStatusCode().value()).as("no idempotency key: 4xx, never 5xx").isLessThan(500);
        // tenant B's reception with B's own step-up against A's patient/proxy
        String brec = loginAndGetAccessToken(b.reception());
        assertThat(clinicRevoke(brec, a.subject(), a.proxy(), stepUpFor(brec, a.proxy()), "x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(row(a, a.proxy())).as("A untouched").matches("active/FULL/-|revoked/FULL/clinic");
        if (row(a, a.proxy()).startsWith("revoked")) {
            // only the key-less call could have done it: say so explicitly
            assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
        assertThat(audits(b, a.proxy(), "patient.proxy_revoked")).isZero();
    }

    @Test
    void iApi11_aPendingInviteIsRevokedByTheClinic_andItsOtpIsCleared() throws Exception {
        Clinic c = clinic("pending", "FULL");
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO proxy_invites (token_hash, tenant_id, proxy_id, otp_hash, expires_at) VALUES (?,?,?, 'hash', now() + interval '10 minutes')", "a".repeat(64), c.t().id(), c.proxy()));
        String rec = loginAndGetAccessToken(c.reception());
        assertThat(clinicRevoke(rec, c.subject(), c.proxy(), stepUpFor(rec, c.proxy()), "Wrong number").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(row(c, c.proxy())).isEqualTo("revoked/FULL/clinic");
        Map<String, Object> inv = inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT used_at IS NOT NULL AS used, otp_hash FROM proxy_invites WHERE proxy_id = ?", c.proxy()));
        assertThat(inv).containsEntry("used", true).containsEntry("otp_hash", null);
        assertThat(members(c)).isEmpty();
    }

    @Test
    void iApi12_anAppointmentBookedByTheCaregiverSurvivesTheRevoke_andNamesWhoBookedIt() {
        Clinic c = clinic();
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID apt = inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, acted_by_patient_id, acted_by_relation) "
                + "VALUES (?,?,?,?,?,?,'daughter') RETURNING id", UUID.class, c.t().id(), c.subject(), c.reception().id(), java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(15, ChronoUnit.MINUTES)), c.caregiver()));
        assertThat(change(c.edge(), c.t().id(), c.proxy(), c.subjectRef(), "BOOK_ONLY", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // a BOOK_ONLY caregiver may still cancel what she booked
        ResponseEntity<Map> detail = exchange(c.base() + "/appointments/" + apt + "?patientRef=" + c.subjectRef(), HttpMethod.GET, authed(c.edge()), Map.class);
        assertThat(detail.getBody()).containsEntry("status", "scheduled").containsEntry("bookedByRelation", "daughter");
        assertThat(change(c.edge(), c.t().id(), c.proxy(), c.subjectRef(), null, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, apt))).isEqualTo("scheduled");
        ResponseEntity<Map> cancel = exchange(c.base() + "/appointments/" + apt + "/cancel", HttpMethod.POST,
                authedJsonBody(c.edge(), Map.of("patientRef", c.subjectRef(), "reasonCode", "illness", "actedByRef", c.caregiverRef())), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel.getBody()).containsEntry("code", "not-a-proxy");
        // the patient herself still can
        ResponseEntity<Map> own = exchange(c.base() + "/appointments/" + apt + "/cancel", HttpMethod.POST,
                authedJsonBody(c.edge(), Map.of("patientRef", c.subjectRef(), "reasonCode", "illness")), Map.class);
        assertThat(own.getStatusCode().value()).as("%s", own.getBody()).isLessThan(300);
    }
}
