package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA (S10 PLT-004): MFA re-enrolment needs the current factor, pending secret, recovery codes, owner reset.
 * Written from the story's acceptance criteria and API contract.
 */
class S10QaMfaRecoveryTest extends ApiTestBase {

    @Autowired
    private JwtEncoder jwtEncoder;

    // ---------- helpers ----------
    private static byte[] base32(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int bits = 0, val = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : s.toUpperCase().replace("=", "").toCharArray()) {
            val = (val << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) { out.write((val >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }

    private static String totp(byte[] secret, int stepOffset) {
        try {
            long counter = Instant.now().getEpochSecond() / 30 + stepOffset;
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int o = h[h.length - 1] & 0xF;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** TOTP replay guard stores the last accepted step; tests need fresh steps, so clear it. */
    private void forgetLastStep(SeededStaff s) {
        inTenantTx(s.tenantId(), () -> jdbc.update("UPDATE staff SET mfa_last_used_step = NULL WHERE id = ?", s.id()));
    }

    private String challenge(SeededStaff s) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("challengeId");
        return (String) r.getBody().get("challengeId");
    }

    private ResponseEntity<Map> verifyTotp(String challenge, String code) {
        return http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", challenge, "code", code)), Map.class);
    }

    /** Full MFA sign-in with the seeded (original) secret; returns the whole token body. */
    private Map<String, Object> mfaSignIn(SeededStaff s) {
        forgetLastStep(s);
        ResponseEntity<Map> r = verifyTotp(challenge(s), totp(TOTP_SECRET, 0));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> recoveryVerify(String challenge, String code) {
        return http.postForEntity(url("/v1/auth/mfa/recovery-verify"), jsonBody(Map.of("challengeToken", challenge, "recoveryCode", code)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claims(String token) {
        try {
            return objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String stepUp(String access, UUID subject) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject(subject.toString()).claim("purpose", "step_up").claim("sid", claims(access).get("sid"));
        com.nabd.hms.shared.security.TokenType.STEP_UP.stamp(b, com.nabd.hms.shared.security.Audience.STEP_UP);
        return jwtEncoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> enroll(String access, Map<String, Object> body, String stepUp) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(access);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        if (stepUp != null) h.set("X-Step-Up-Token", stepUp);
        return exchange("/v1/auth/mfa/enroll", HttpMethod.POST, new HttpEntity<>(writeJson(body), h), Map.class);
    }

    private ResponseEntity<Map> confirm(String access, String code) {
        return exchange("/v1/auth/mfa/confirm", HttpMethod.POST, authedJsonBody(access, Map.of("code", code)), Map.class);
    }

    private Map<String, Object> staffRow(SeededStaff s) {
        return inTenantTx(s.tenantId(), () -> jdbc.queryForMap(
                "SELECT mfa_enabled, mfa_secret_enc, mfa_pending_secret_enc, mfa_reset_required FROM staff WHERE id = ?", s.id()));
    }

    private int liveCodes(SeededStaff s) {
        return inTenantTx(s.tenantId(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM mfa_recovery_codes WHERE staff_id = ? AND used_at IS NULL AND revoked_at IS NULL", Integer.class, s.id()));
    }

    private int audits(SeededStaff s, String action) {
        return inTenantTx(s.tenantId(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, s.id()));
    }

    private String writeJson(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Re-enrol (via step-up), confirm with the new secret; returns {codes, secret}. */
    @SuppressWarnings("unchecked")
    private Object[] rotate(SeededStaff s, String access) {
        ResponseEntity<Map> e = enroll(access, Map.of(), stepUp(access, s.id()));
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] secret = base32((String) e.getBody().get("secretBase32"));
        forgetLastStep(s); // replay guard: a step already used at sign-in may not be used again
        ResponseEntity<Map> c = confirm(access, totp(secret, 0));
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Object[]{(List<String>) c.getBody().get("recoveryCodes"), secret};
    }

    private SeededStaff mfaStaff(SeededTenant t, String tag, UUID role) {
        return seedStaff(t, role, tag + "@q.com", "+9194" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000), true);
    }

    // ================= AC1: re-enrolment of an enabled account needs the current factor =================
    @Test
    void ac1_reEnrolWithoutProof_403StepUpRequired_andActiveSecretUnchanged() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac1a", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        Object before = staffRow(s).get("mfa_secret_enc");

        ResponseEntity<Map> none = enroll(access, Map.of(), null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(none.getBody().get("type"))).endsWith("step-up-required");

        ResponseEntity<Map> wrong = enroll(access, Map.of("currentCode", "000000"), null);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> garbageStepUp = enroll(access, Map.of(), "not-a-token");
        assertThat(garbageStepUp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // a step-up minted for somebody else is not proof for this account
        ResponseEntity<Map> otherSubject = enroll(access, Map.of(), stepUp(access, UUID.randomUUID()));
        assertThat(otherSubject.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> after = staffRow(s);
        assertThat((byte[]) after.get("mfa_secret_enc")).isEqualTo((byte[]) before);
        assertThat(after.get("mfa_pending_secret_enc")).isNull();
        assertThat(after.get("mfa_enabled")).isEqualTo(true);
    }

    @Test
    void ac1_reEnrolWithCurrentTotp_succeeds_andWritesPendingNotActive() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac1b", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        Object before = staffRow(s).get("mfa_secret_enc");
        forgetLastStep(s);
        ResponseEntity<Map> r = enroll(access, Map.of("currentCode", totp(TOTP_SECRET, 0)), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("secretBase32")).isNotNull();
        Map<String, Object> row = staffRow(s);
        assertThat((byte[]) row.get("mfa_secret_enc")).isEqualTo((byte[]) before);
        assertThat(row.get("mfa_pending_secret_enc")).isNotNull();
    }

    @Test
    void ac1_reEnrolWithValidStepUpToken_succeeds() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac1c", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        assertThat(enroll(access, Map.of(), stepUp(access, s.id())).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac1_firstEnrolmentOfAnAccountWithoutMfa_needsNoProof() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "ac1d@q.com", "+919400300041", false);
        String access = loginAndGetAccessToken(s);
        ResponseEntity<Map> r = enroll(access, Map.of(), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(staffRow(s).get("mfa_enabled")).isEqualTo(false); // not active until confirmed
    }

    // ================= AC2: old authenticator keeps working until confirm =================
    @Test
    void ac2_signInBeforeConfirm_oldAuthenticatorStillWorks_pendingOneDoesNot() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac2", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        ResponseEntity<Map> e = enroll(access, Map.of(), stepUp(access, s.id()));
        byte[] pending = base32((String) e.getBody().get("secretBase32"));

        // new secret is NOT accepted at the challenge yet
        forgetLastStep(s);
        ResponseEntity<Map> withPending = verifyTotp(challenge(s), totp(pending, 0));
        assertThat(withPending.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // old secret is
        forgetLastStep(s);
        ResponseEntity<Map> withOld = verifyTotp(challenge(s), totp(TOTP_SECRET, 0));
        assertThat(withOld.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withOld.getBody()).containsKey("accessToken");
    }

    // ================= AC3: confirm rotates recovery codes =================
    @Test
    @SuppressWarnings("unchecked")
    void ac3_confirmReturnsExactlyEightDistinctCodes_storedOnlyAsSha256Hashes_andSwapsSecret() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac3a", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        Object[] r = rotate(s, access);
        List<String> codes = (List<String>) r[0];
        assertThat(codes).hasSize(8).doesNotHaveDuplicates().allMatch(c -> c != null && c.length() >= 8);
        Set<String> hashes = Set.copyOf(inTenantTx(s.tenantId(), () -> jdbc.queryForList(
                "SELECT code_hash FROM mfa_recovery_codes WHERE staff_id = ? AND revoked_at IS NULL AND used_at IS NULL", String.class, s.id())));
        assertThat(hashes).hasSize(8);
        for (String c : codes) {
            assertThat(c).isNotIn(hashes);
            assertThat(hashes).contains(sha256(c));
        }
        // secret swapped: pending cleared, new secret is the active one
        Map<String, Object> row = staffRow(s);
        assertThat(row.get("mfa_pending_secret_enc")).isNull();
        assertThat(cipher.decrypt((byte[]) row.get("mfa_secret_enc"))).isEqualTo((byte[]) r[1]);
        // and now the OLD authenticator is dead, the NEW one signs in
        forgetLastStep(s);
        assertThat(verifyTotp(challenge(s), totp(TOTP_SECRET, 0)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        forgetLastStep(s);
        assertThat(verifyTotp(challenge(s), totp((byte[]) r[1], 0)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac3_confirmWithWrongCode_is401MfaFailed_andNothingChanges() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac3b", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        enroll(access, Map.of(), stepUp(access, s.id()));
        Object active = staffRow(s).get("mfa_secret_enc");
        ResponseEntity<Map> bad = confirm(access, "000000");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(bad.getBody().get("type"))).endsWith("mfa-failed");
        assertThat((byte[]) staffRow(s).get("mfa_secret_enc")).isEqualTo((byte[]) active);
        assertThat(liveCodes(s)).isZero();
    }

    @Test
    void ac3_confirmWithNothingPending_isRefused() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac3c", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        forgetLastStep(s);
        // a valid code for the ACTIVE secret must not be accepted as a confirmation
        assertThat(confirm(access, totp(TOTP_SECRET, 0)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(liveCodes(s)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac3_secondConfirmationRevokesEveryPreviouslyUnusedCode_exactlyEightLive() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac3d", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        List<String> first = (List<String>) rotate(s, access)[0];
        forgetLastStep(s);
        List<String> second = (List<String>) rotate(s, access)[0];
        assertThat(liveCodes(s)).isEqualTo(8);
        assertThat(inTenantTx(s.tenantId(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM mfa_recovery_codes WHERE staff_id = ? AND revoked_at IS NOT NULL", Integer.class, s.id()))).isEqualTo(8);
        assertThat(second).doesNotContainAnyElementsOf(first);
        // old code refused at the challenge, a new one accepted
        assertThat(recoveryVerify(challenge(s), first.get(0)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> ok = recoveryVerify(challenge(s), second.get(0));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================= AC4: recovery code at the challenge =================
    @Test
    @SuppressWarnings("unchecked")
    void ac4_validRecoveryCode_issuesSession_isSingleUse_andIsAudited() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac4a", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        List<String> codes = (List<String>) rotate(s, access)[0];

        ResponseEntity<Map> r = recoveryVerify(challenge(s), codes.get(0));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("accessToken")).isNotNull();
        assertThat(r.getBody().get("refreshToken")).isNotNull();
        assertThat(r.getBody().get("remainingCodes")).isEqualTo(7);
        // the issued session really works
        ResponseEntity<Map[]> sessions = exchange("/v1/auth/sessions", HttpMethod.GET, authed((String) r.getBody().get("accessToken")), Map[].class);
        assertThat(sessions.getStatusCode()).isEqualTo(HttpStatus.OK);

        // reuse refused with a fresh challenge
        ResponseEntity<Map> again = recoveryVerify(challenge(s), codes.get(0));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(again.getBody().get("type"))).endsWith("recovery-code-invalid");
        assertThat(liveCodes(s)).isEqualTo(7);
        assertThat(audits(s, "auth.recovery_code_used")).isEqualTo(1);
        // the audit row carries no code text
        String audit = inTenantTx(s.tenantId(), () -> jdbc.queryForObject(
                "SELECT coalesce(before::text,'')||coalesce(after::text,'')||action FROM audit_log WHERE action='auth.recovery_code_used' AND entity_id=?", String.class, s.id()));
        assertThat(audit).doesNotContain(codes.get(0));
    }

    @Test
    void ac4_invalidInputs_challengeAndCode() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac4b", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        rotate(s, access);
        ResponseEntity<Map> badCode = recoveryVerify(challenge(s), "ZZZZZZZZZZ");
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(badCode.getBody().get("type"))).endsWith("recovery-code-invalid");
        ResponseEntity<Map> garbage = recoveryVerify("garbage.token.value", "ABCDEFGHIJ");
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(garbage.getBody().get("type"))).endsWith("challenge-invalid");
        // an access token is not a challenge
        ResponseEntity<Map> wrongType = recoveryVerify(access, "ABCDEFGHIJ");
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // blank code is a validation error, not a 500
        ResponseEntity<Map> blank = recoveryVerify(challenge(s), "");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(liveCodes(s)).isEqualTo(8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac4_sameCodeRacedInParallel_succeedsExactlyOnce() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac4c", seedFullAccessRole(t.id()));
        String access = (String) mfaSignIn(s).get("accessToken");
        List<String> codes = (List<String>) rotate(s, access)[0];
        String c1 = challenge(s), c2 = challenge(s), c3 = challenge(s), c4 = challenge(s);
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<HttpStatus>> fs = new java.util.ArrayList<>();
            for (String ch : List.of(c1, c2, c3, c4)) {
                Callable<HttpStatus> call = () -> HttpStatus.valueOf(recoveryVerify(ch, codes.get(1)).getStatusCode().value());
                fs.add(ex.submit(call));
            }
            long ok = 0;
            for (Future<HttpStatus> f : fs) if (f.get() == HttpStatus.OK) ok++;
            assertThat(ok).isEqualTo(1);
        } finally {
            ex.shutdownNow();
        }
        assertThat(liveCodes(s)).isEqualTo(7);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac4_codeOfAnotherStaffMemberOrTenant_isNotAccepted() {
        SeededTenant a = seedTenant(), b = seedTenant();
        SeededStaff sa = mfaStaff(a, "ac4d1", seedFullAccessRole(a.id()));
        SeededStaff sb = mfaStaff(b, "ac4d2", seedFullAccessRole(b.id()));
        List<String> codesA = (List<String>) rotate(sa, (String) mfaSignIn(sa).get("accessToken"))[0];
        rotate(sb, (String) mfaSignIn(sb).get("accessToken"));
        assertThat(recoveryVerify(challenge(sb), codesA.get(0)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(liveCodes(sa)).isEqualTo(8); // A's code untouched
    }

    @Test
    void ac4_repeatedWrongRecoveryCodes_lockTheAccount_423_evenForTheRightCodeAfterwards() {
        SeededTenant t = seedTenant();
        SeededStaff s = mfaStaff(t, "ac4e", seedFullAccessRole(t.id()));
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) rotate(s, (String) mfaSignIn(s).get("accessToken"))[0];
        String c0 = challenge(s); // one challenge reused: login itself may be refused once the account is locked
        HttpStatus last = null;
        for (int i = 0; i < 25 && last != HttpStatus.LOCKED; i++) {
            last = HttpStatus.valueOf(recoveryVerify(c0, "WRONGCODE" + i).getStatusCode().value());
        }
        assertThat(last).as("account locks after repeated wrong recovery codes").isEqualTo(HttpStatus.LOCKED);
        ResponseEntity<Map> right = recoveryVerify(c0, codes.get(0));
        assertThat(right.getStatusCode()).as("even the right code is refused while locked").isEqualTo(HttpStatus.LOCKED);
        assertThat(liveCodes(s)).isEqualTo(8);
    }

    // ================= AC5/AC6: owner reset =================
    private record World(SeededTenant t, SeededStaff owner, String ownerAccess, SeededStaff doctor, Map<String, Object> doctorTokens, List<String> doctorCodes) {
    }

    @SuppressWarnings("unchecked")
    private World world() {
        SeededTenant t = seedTenant();
        SeededStaff owner = mfaStaff(t, "own" + UUID.randomUUID().toString().substring(0, 5), seedFullAccessRole(t.id()));
        String oa = (String) mfaSignIn(owner).get("accessToken");
        UUID docRole = seedRole(t.id(), "Doc" + UUID.randomUUID().toString().substring(0, 5), false, fullGrant("clinical"));
        SeededStaff doc = mfaStaff(t, "doc" + UUID.randomUUID().toString().substring(0, 5), docRole);
        Map<String, Object> dt = mfaSignIn(doc);
        List<String> codes = (List<String>) rotate(doc, (String) dt.get("accessToken"))[0];
        return new World(t, owner, oa, doc, dt, codes);
    }

    private ResponseEntity<Map> reset(String access, UUID target, Object body, String stepUp) {
        return exchange("/v1/staff/" + target + "/mfa/reset", HttpMethod.POST, authedJsonBodyWithHeader(access, "X-Step-Up-Token", stepUp, body), Map.class);
    }

    @Test
    void ac5_ownerWithStepUpResetsDoctor_auditSessionsRevoked_andNextSignInForcesEnrolment() {
        World w = world();
        ResponseEntity<Map> r = reset(w.ownerAccess(), w.doctor().id(), Map.of("reasonCode", "lost_device", "note", "dropped in a lake"), stepUp(w.ownerAccess(), w.owner().id()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // audit row, actor = owner, no free-text note stored
        Map<String, Object> audit = inTenantTx(w.t().id(), () -> jdbc.queryForMap(
                "SELECT actor_id, entity_id, after::text AS after_txt FROM audit_log WHERE action = 'staff.mfa_reset' AND entity_id = ?", w.doctor().id()));
        assertThat(audit.get("actor_id")).isEqualTo(w.owner().id());
        assertThat(String.valueOf(audit.get("after_txt"))).contains("lost_device").doesNotContain("lake");

        // sessions: refresh token dead, access token dead, DB shows revocation
        ResponseEntity<Map> refresh = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", w.doctorTokens().get("refreshToken"))), Map.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> withOldAccess = exchange("/v1/auth/sessions", HttpMethod.GET, authed((String) w.doctorTokens().get("accessToken")), String.class);
        assertThat(withOldAccess.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inTenantTx(w.t().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_at IS NULL", Integer.class, w.doctor().id()))).isZero();

        // old recovery codes are dead and MFA is off
        Map<String, Object> row = staffRow(w.doctor());
        assertThat(row.get("mfa_enabled")).isEqualTo(false);
        assertThat(row.get("mfa_secret_enc")).isNull();
        assertThat(row.get("mfa_reset_required")).isEqualTo(true);
        assertThat(liveCodes(w.doctor())).isZero();

        // next sign-in: PIN alone does not yield a session, it yields an enrolment (setup) token
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", w.doctor().slug(), "email", w.doctor().email(), "pin", STAFF_PIN)), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).containsKey("setupToken").doesNotContainKey("accessToken");

        // completing enrolment clears the flag, returns 8 codes, and the next sign-in is a normal MFA challenge
        String setup = (String) login.getBody().get("setupToken");
        ResponseEntity<Map> en = http.postForEntity(url("/v1/auth/mfa/enroll"), jsonBody(Map.of("setupToken", setup)), Map.class);
        assertThat(en.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] secret = base32((String) en.getBody().get("secretBase32"));
        forgetLastStep(w.doctor());
        ResponseEntity<Map> cf = http.postForEntity(url("/v1/auth/mfa/confirm"), jsonBody(Map.of("setupToken", setup, "code", totp(secret, 0))), Map.class);
        assertThat(cf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) cf.getBody().get("recoveryCodes")).hasSize(8);
        assertThat(staffRow(w.doctor()).get("mfa_reset_required")).isEqualTo(false);
        forgetLastStep(w.doctor());
        ResponseEntity<Map> again = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", w.doctor().slug(), "email", w.doctor().email(), "pin", STAFF_PIN)), Map.class);
        assertThat(again.getBody()).containsKey("challengeId");
    }

    @Test
    void ac5_resetRefusals_noStepUp_wrongSubjectStepUp_badReason_unknownId_unauthenticated() {
        World w = world();
        Map<String, Object> ok = Map.of("reasonCode", "other");
        ResponseEntity<Map> none = reset(w.ownerAccess(), w.doctor().id(), ok, null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(none.getBody().get("type"))).endsWith("step-up-required");
        // step-up that belongs to the TARGET, not the caller
        assertThat(reset(w.ownerAccess(), w.doctor().id(), ok, stepUp(w.ownerAccess(), w.doctor().id())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reset(w.ownerAccess(), w.doctor().id(), Map.of("reasonCode", "because"), stepUp(w.ownerAccess(), w.owner().id())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reset(w.ownerAccess(), w.doctor().id(), Map.of(), stepUp(w.ownerAccess(), w.owner().id())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reset(w.ownerAccess(), UUID.randomUUID(), ok, stepUp(w.ownerAccess(), w.owner().id())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> anon = exchange("/v1/staff/" + w.doctor().id() + "/mfa/reset", HttpMethod.POST, jsonBody(ok), Map.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // nothing happened to the doctor
        assertThat(staffRow(w.doctor()).get("mfa_enabled")).isEqualTo(true);
        assertThat(audits(w.doctor(), "staff.mfa_reset")).isZero();
        assertThat(liveCodes(w.doctor())).isEqualTo(8);
    }

    @Test
    void ac6_receptionistWithoutStaffEdit_gets403_evenWithAStepUpToken_andNothingChanges() {
        World w = world();
        UUID recRole = seedRole(w.t().id(), "Rec" + UUID.randomUUID().toString().substring(0, 5), false, fullGrant("patients"), fullGrant("queue"));
        SeededStaff rec = seedStaff(w.t(), recRole, "rec" + UUID.randomUUID().toString().substring(0, 5) + "@q.com", "+919400300077", false);
        String ra = loginAndGetAccessToken(rec);
        ResponseEntity<Map> r = reset(ra, w.doctor().id(), Map.of("reasonCode", "lost_device"), stepUp(ra, rec.id()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        if (r.getBody() != null) assertThat(String.valueOf(r.getBody().get("type"))).doesNotContain("step-up"); // plain 403, not the step-up error
        assertThat(staffRow(w.doctor()).get("mfa_enabled")).isEqualTo(true);
        assertThat(audits(w.doctor(), "staff.mfa_reset")).isZero();
        // doctor's session still works
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed((String) w.doctorTokens().get("accessToken")), Map[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac5_crossTenant_ownerOfTenantBCannotResetStaffOfTenantA_404() {
        World a = world();
        World b = world();
        ResponseEntity<Map> r = reset(b.ownerAccess(), a.doctor().id(), Map.of("reasonCode", "lost_device"), stepUp(b.ownerAccess(), b.owner().id()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(staffRow(a.doctor()).get("mfa_enabled")).isEqualTo(true);
        assertThat(audits(a.doctor(), "staff.mfa_reset")).isZero();
    }

    @Test
    void ac5_resetIsIdempotent_secondCallStill204() {
        World w = world();
        String su = stepUp(w.ownerAccess(), w.owner().id());
        assertThat(reset(w.ownerAccess(), w.doctor().id(), Map.of("reasonCode", "left_clinic"), su).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reset(w.ownerAccess(), w.doctor().id(), Map.of("reasonCode", "left_clinic"), su).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(staffRow(w.doctor()).get("mfa_reset_required")).isEqualTo(true);
    }
}
