package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-004: pending-secret re-enrolment, recovery codes, owner MFA reset. Each test uses its own accounts (TOTP replay guard is per account). */
class Plt004MfaTest extends ApiTestBase {

    private SeededStaff mfaStaff(SeededTenant t, UUID role, String email, String mobile) {
        return seedStaff(t, role, email, mobile, true);
    }

    private String challengeOf(SeededStaff s) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        return (String) r.getBody().get("challengeId");
    }

    private String accessVia(SeededStaff s, int offset) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeOf(s), "code", totpCodeAtOffset(offset))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    private String stepUp(String access, int offset) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/mfa/verify"),
                new HttpEntity<>("{\"challengeId\":\"x\",\"code\":\"" + totpCodeAtOffset(offset) + "\"}", h), Map.class);
        return (String) r.getBody().get("stepUpToken");
    }

    private static String totpFor(String base32, int offset) {
        byte[] secret = org.bouncycastle.util.encoders.Base32.decode(base32);
        return new TotpService().generate(secret, java.time.Instant.now().getEpochSecond() / 30 + offset);
    }

    private byte[] dbBytes(SeededStaff s, String col) {
        return inTenantTx(s.tenantId(), () -> jdbc.query("SELECT " + col + " FROM staff WHERE id = ?",
                rs -> rs.next() ? rs.getBytes(1) : null, s.id()));
    }

    private void resetStep(SeededStaff s) { // lets one test walk the ±1 TOTP window more than three times
        inTenantTx(s.tenantId(), () -> jdbc.update("UPDATE staff SET mfa_last_used_step = NULL WHERE id = ?", s.id()));
    }

    private int audits(SeededStaff s, String action) {
        return inTenantTx(s.tenantId(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, s.id()));
    }

    // ---- MfaReEnrolTest ----

    @Test
    void reEnrolWithoutCurrentFactorIs403AndActiveSecretUnchanged() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, seedFullAccessRole(t.id()), "re1@a.com", "+919400000001");
        String access = accessVia(s, -1);
        byte[] before = dbBytes(s, "mfa_secret_enc");

        ResponseEntity<Map> none = exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(access), Map.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(none.getBody().get("type"))).endsWith("step-up-required");
        ResponseEntity<Map> wrong = exchange("/v1/auth/mfa/enroll", HttpMethod.POST,
                authedJsonBody(access, Map.of("currentCode", "000000")), Map.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(dbBytes(s, "mfa_secret_enc")).isEqualTo(before);
        assertThat(dbBytes(s, "mfa_pending_secret_enc")).isNull();
    }

    @Test
    void oldSecretWorksUntilConfirm_thenCodesRotate() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, seedFullAccessRole(t.id()), "re2@a.com", "+919400000002");
        String oldHash = com.nabd.hms.common.OpaqueTokens.sha256Hex("OLDCODE001");
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO mfa_recovery_codes (tenant_id, staff_id, code_hash) VALUES (?,?,?)", t.id(), s.id(), oldHash));
        String access = accessVia(s, -1);

        ResponseEntity<Map> enroll = exchange("/v1/auth/mfa/enroll", HttpMethod.POST,
                authedJsonBody(access, Map.of("currentCode", totpCodeAtOffset(0))), Map.class);
        assertThat(enroll.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newSecret = (String) enroll.getBody().get("secretBase32");

        // pending is not active: the old authenticator still signs in
        accessVia(s, 1);

        resetStep(s);
        // confirm with the new secret: 8 new codes, old unused code revoked
        ResponseEntity<Map> bad = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(access, Map.of("code", "000000")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> confirm = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(access, Map.of("code", totpFor(newSecret, 0))), Map.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> codes = (List<String>) confirm.getBody().get("recoveryCodes");
        assertThat(codes).hasSize(8).doesNotContain("OLDCODE001");
        assertThat(dbBytes(s, "mfa_pending_secret_enc")).isNull();

        ResponseEntity<Map> old = recovery(challengeOf(s), "OLDCODE001");
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(old.getBody().get("type"))).endsWith("recovery-code-invalid");
        assertThat(recovery(challengeOf(s), codes.get(0)).getStatusCode()).isEqualTo(HttpStatus.OK);
        resetStep(s);
        // the old authenticator is gone: its code no longer completes a challenge
        ResponseEntity<Map> oldTotp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeOf(s), "code", totpCodeAtOffset(0))), Map.class);
        assertThat(oldTotp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reEnrolWithStepUpTokenAlsoWorks() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, seedFullAccessRole(t.id()), "re3@a.com", "+919400000003");
        String access = accessVia(s, -1);
        String su = stepUp(access, 0);
        ResponseEntity<Map> r = exchange("/v1/auth/mfa/enroll", HttpMethod.POST,
                authedJsonBodyWithHeader(access, "X-Step-Up-Token", su, Map.of()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dbBytes(s, "mfa_pending_secret_enc")).isNotNull();
    }

    // ---- RecoveryCodeSingleUseTest ----

    @Test
    void recoveryCodeIsSingleUseAuditedAndLocksOutGuessing() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, seedFullAccessRole(t.id()), "rc1@a.com", "+919400000004");
        String access = accessVia(s, -1);
        ResponseEntity<Map> enroll = exchange("/v1/auth/mfa/enroll", HttpMethod.POST,
                authedJsonBody(access, Map.of("currentCode", totpCodeAtOffset(0))), Map.class);
        ResponseEntity<Map> confirm = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(access, Map.of("code", totpFor((String) enroll.getBody().get("secretBase32"), 1))), Map.class);
        List<String> codes = (List<String>) confirm.getBody().get("recoveryCodes");

        assertThat(recovery("garbage", codes.get(0)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // CHALLENGE_INVALID
        ResponseEntity<Map> ok = recovery(challengeOf(s), codes.get(0).toLowerCase()); // case-insensitive entry
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsKeys("accessToken", "refreshToken");
        assertThat(ok.getBody().get("remainingCodes")).isEqualTo(7);
        assertThat(recovery(challengeOf(s), codes.get(0)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // reuse
        assertThat(audits(s, "auth.recovery_code_used")).isEqualTo(1);

        // repeated wrong codes hit the account lockout (423)
        String challenge = challengeOf(s); // login itself is refused once locked, so take the challenge first
        HttpStatus last = HttpStatus.OK;
        for (int i = 0; i < 12 && last != HttpStatus.LOCKED; i++) {
            last = (HttpStatus) recovery(challenge, "WRONGCODE" + i).getStatusCode();
        }
        assertThat(last).isEqualTo(HttpStatus.LOCKED);
    }

    private ResponseEntity<Map> recovery(String challenge, String code) {
        return http.postForEntity(url("/v1/auth/mfa/recovery-verify"),
                jsonBody(Map.of("challengeToken", challenge, "recoveryCode", code)), Map.class);
    }

    // ---- MfaAdminResetAuthzTest ----

    @Test
    void ownerWithStepUpResetsDoctorMfa() {
        SeededTenant t = seedTenant();
        SeededStaff owner = mfaStaff(t, seedFullAccessRole(t.id()), "own1@a.com", "+919400000005");
        SeededStaff doctor = mfaStaff(t, seedBreakGlassDoctorRole(t.id()), "doc1@a.com", "+919400000006");
        String ownerAccess = accessVia(owner, -1);
        String doctorAccess = accessVia(doctor, -1);
        String su = stepUp(ownerAccess, 0);

        // no step-up -> 403
        assertThat(reset(ownerAccess, doctor.id(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reset(ownerAccess, UUID.randomUUID(), su).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reset(ownerAccess, doctor.id(), su).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reset(ownerAccess, doctor.id(), su).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // idempotent

        assertThat(audits(doctor, "staff.mfa_reset")).isEqualTo(2);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_at IS NULL", Integer.class, doctor.id()))).isZero();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_reason = 'admin_revoke'", Integer.class, doctor.id()))).isPositive();
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(doctorAccess), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // next sign-in forces enrolment, then returns to the normal MFA challenge
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", doctor.slug(), "email", doctor.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(login.getBody()).containsKey("setupToken").doesNotContainKey("challengeId");
        String setup = (String) login.getBody().get("setupToken");
        ResponseEntity<Map> enroll = exchange("/v1/auth/mfa/enroll", HttpMethod.POST, jsonBody(Map.of("setupToken", setup)), Map.class);
        assertThat(enroll.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> confirm = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                jsonBody(Map.of("code", totpFor((String) enroll.getBody().get("secretBase32"), 0), "setupToken", setup)), Map.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(challengeOf(doctor)).isNotBlank();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT mfa_reset_required FROM staff WHERE id = ?", Boolean.class, doctor.id()))).isFalse();
    }

    @Test
    void receptionistCannotResetAndTenantsAreIsolated() {
        SeededTenant t = seedTenant();
        SeededStaff rec = seedStaff(t, seedRole(t.id(), "Receptionist", false, fullGrant("queue")), "rec1@a.com", "+919400000007", false);
        SeededStaff doctor = mfaStaff(t, seedBreakGlassDoctorRole(t.id()), "doc2@a.com", "+919400000008");
        assertThat(reset(loginAndGetAccessToken(rec), doctor.id(), "x").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbBytes(doctor, "mfa_secret_enc")).isNotNull();

        SeededTenant other = seedTenant();
        SeededStaff foreignOwner = mfaStaff(other, seedFullAccessRole(other.id()), "own2@a.com", "+919400000009");
        String access = accessVia(foreignOwner, -1);
        assertThat(reset(access, doctor.id(), stepUp(access, 0)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(dbBytes(doctor, "mfa_secret_enc")).isNotNull();
    }

    @Test
    void recoveryCodesTableIsTenantIsolated() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        SeededStaff s = mfaStaff(a, seedFullAccessRole(a.id()), "iso@a.com", "+919400000010");
        inTenantTx(a.id(), () -> jdbc.update("INSERT INTO mfa_recovery_codes (tenant_id, staff_id, code_hash) VALUES (?,?,?)", a.id(), s.id(), "h"));
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM mfa_recovery_codes", Integer.class))).isZero();
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM mfa_recovery_codes", Integer.class))).isEqualTo(1);
    }

    private ResponseEntity<Void> reset(String access, UUID target, String stepUp) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (stepUp != null) {
            h.set("X-Step-Up-Token", stepUp);
        }
        return http.exchange(url("/v1/staff/" + target + "/mfa/reset"), HttpMethod.POST,
                new HttpEntity<>("{\"reasonCode\":\"lost_device\"}", h), Void.class);
    }
}
