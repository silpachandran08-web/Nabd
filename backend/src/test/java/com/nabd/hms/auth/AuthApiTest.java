package com.nabd.hms.auth;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.WhatsAppOtpSender;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@Import(AuthApiTest.OtpCaptorConfig.class)
class AuthApiTest extends ApiTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private CapturingOtpSender otpSender;

    // ---- login ----

    @Test
    void loginWithCorrectPinSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919000000001", false);

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("accessToken", "refreshToken", "expiresIn");
    }

    @Test
    void accessTokenMarksOnlyTheBuiltInOwnerRoleAsOwner() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "own@a.com", "+919000000071", false);
        // same full grants, but a custom role — must not be treated as the owner
        UUID manager = seedRole(tenant.id(), "Manager", false, fullGrant("nursing"), fullGrant("clinical"), fullGrant("queue"),
                fullGrant("reports"), fullGrant("setup"));
        SeededStaff notOwner = seedStaff(tenant, manager, "mgr@a.com", "+919000000072", false);

        assertThat(decodeClaims(loginAndGetAccessToken(owner)).get("owner")).isEqualTo(true);
        assertThat(decodeClaims(loginAndGetAccessToken(notOwner)).get("owner")).isEqualTo(false);
    }

    @Test
    void loginWithWrongPinFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919000000002", false);

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", "9999")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("type")).asString().contains("invalid-credentials");
    }

    @Test
    void loginWithUnknownTenantSlugFails() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", "does-not-exist", "email", "nobody@a.com", "pin", "1234")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginValidatesPinShape() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", "clinic-a", "email", "a@a.com", "pin", "abcd")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- lockout (regression: still-locked hits must not renew the lock — see AuthService.enforceAccountLockout) ----

    @Test
    void repeatedFailuresLockAccountAndFurtherHitsDoNotExtendTheWindow() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "lockout@a.com", "+919000000003", false);

        for (int i = 0; i < 5; i++) {
            http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                    "tenantSlug", tenant.slug(), "email", staff.email(), "pin", "0000")), Map.class);
        }
        ResponseEntity<Map> locked = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", "0000")), Map.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        Integer attemptsAfterFirstLock = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_attempts WHERE staff_id = ?", Integer.class, staff.id());

        // three more hits while still locked, even with the CORRECT pin — none of these may insert
        // a new attempt row or the lock would renew forever (the exact bug fixed this session)
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> stillLocked = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                    "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
            assertThat(stillLocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        }

        Integer attemptsAfterHammering = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_attempts WHERE staff_id = ?", Integer.class, staff.id());
        assertThat(attemptsAfterHammering).isEqualTo(attemptsAfterFirstLock);
    }

    // ---- sessions / refresh / logout ----

    @Test
    void sessionOwnershipIsEnforcedOnRevoke() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staffA = seedStaff(tenant, roleId, "a@a.com", "+919000000004", false);
        SeededStaff staffB = seedStaff(tenant, roleId, "b@a.com", "+919000000005", false);

        String tokenA = loginAndGetAccessToken(staffA);
        String tokenB = loginAndGetAccessToken(staffB);
        UUID staffBSessionId = currentSessionId(tokenB);

        // staff A must not be able to revoke staff B's session, even within the same tenant
        ResponseEntity<Void> resp = exchange("/v1/auth/sessions/" + staffBSessionId, HttpMethod.DELETE,
                authed(tokenA), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void staffCanRevokeTheirOwnSession() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "self@a.com", "+919000000006", false);
        String token = loginAndGetAccessToken(staff);
        UUID sessionId = currentSessionId(token);

        ResponseEntity<Void> resp = exchange("/v1/auth/sessions/" + sessionId, HttpMethod.DELETE, authed(token), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void listSessionsMarksCurrentSession() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "list@a.com", "+919000000007", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<List> resp = exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> sessions = resp.getBody();
        assertThat(sessions).anyMatch(s -> Boolean.TRUE.equals(s.get("current")));
    }

    @Test
    void logoutRevokesTheSessionSoRefreshFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "logout@a.com", "+919000000008", false);
        Map<?, ?> loginBody = login(tenant, staff);
        String accessToken = (String) loginBody.get("accessToken");
        String refreshToken = (String) loginBody.get("refreshToken");

        ResponseEntity<Void> logoutResp = exchange("/v1/auth/logout", HttpMethod.POST, authed(accessToken), Void.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> refreshResp = http.postForEntity(url("/v1/auth/refresh"),
                jsonBody(Map.of("refreshToken", refreshToken)), Map.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRotatesTokenAndRejectsReuse() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "refresh@a.com", "+919000000009", false);
        Map<?, ?> loginBody = login(tenant, staff);
        String originalRefresh = (String) loginBody.get("refreshToken");

        ResponseEntity<Map> rotated = http.postForEntity(url("/v1/auth/refresh"),
                jsonBody(Map.of("refreshToken", originalRefresh)), Map.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefresh = (String) rotated.getBody().get("refreshToken");
        assertThat(newRefresh).isNotEqualTo(originalRefresh);

        // replaying the now-superseded token is a reuse/compromise signal -> rejected
        ResponseEntity<Map> reused = http.postForEntity(url("/v1/auth/refresh"),
                jsonBody(Map.of("refreshToken", originalRefresh)), Map.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // reuse detection revokes the whole family — even the legitimately-rotated token now fails
        ResponseEntity<Map> afterReuse = http.postForEntity(url("/v1/auth/refresh"),
                jsonBody(Map.of("refreshToken", newRefresh)), Map.class);
        assertThat(afterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithGarbageTokenFails() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/refresh"),
                jsonBody(Map.of("refreshToken", "not-a-real-token")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- WhatsApp OTP ----

    @Test
    void otpRequestForUnknownMobileOrClinicSaysSo() {
        SeededTenant tenant = seedTenant();
        ResponseEntity<Map> resp = http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", "+910000000000")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("detail")).isEqualTo("No active staff member with that mobile number in this clinic.");
        assertThat(otpSender.sentTo).doesNotContainKey("+910000000000");

        ResponseEntity<Map> noClinic = http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", "no-such-clinic", "mobilePhone", "+910000000000")), Map.class);
        assertThat(noClinic.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noClinic.getBody().get("type")).asString().endsWith("clinic-not-found");
    }

    @Test
    void otpRequestThenVerifyLogsIn() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String mobile = "+919000000010";
        seedStaff(tenant, roleId, "otp@a.com", mobile, false);

        ResponseEntity<Void> reqResp = http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", mobile)), Void.class);
        assertThat(reqResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String code = otpSender.sentTo.get(mobile);
        assertThat(code).isNotNull();

        ResponseEntity<Map> verifyResp = http.postForEntity(url("/v1/auth/otp/verify"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "mobilePhone", mobile, "code", code)), Map.class);
        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyResp.getBody()).containsKey("accessToken");
    }

    @Test
    void otpWorksWhateverFormatTheMobileIsTypedInAndGoesToTheNumberOnFile() {
        SeededTenant tenant = seedTenant(); // region IN
        UUID roleId = seedFullAccessRole(tenant.id());
        seedStaff(tenant, roleId, "otpfmt@a.com", "+919812345671", false);

        ResponseEntity<Void> req = http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", "98123 45671")), Void.class);
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String code = otpSender.sentTo.get("+919812345671"); // sent to the stored number, not the typed text
        assertThat(code).isNotNull();
        assertThat(otpSender.sentTo).doesNotContainKey("98123 45671");

        ResponseEntity<Map> verify = http.postForEntity(url("/v1/auth/otp/verify"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "mobilePhone", "00919812345671", "code", code)), Map.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).containsKey("accessToken");

        // an unknown number is refused and nothing is sent
        int before = otpSender.sentTo.size();
        assertThat(http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", "98000 00000")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otpSender.sentTo).hasSize(before);
    }

    @Test
    void otpVerifyWithWrongCodeFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String mobile = "+919000000011";
        seedStaff(tenant, roleId, "otp2@a.com", mobile, false);
        http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", mobile)), Void.class);

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/otp/verify"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "mobilePhone", mobile, "code", "000000")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- MFA challenge + step-up ----

    @Test
    void loginWithMfaEnabledReturnsChallengeThenVerifyIssuesTokens() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "mfa@a.com", "+919000000012", true);

        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).containsKey("challengeId");
        String challengeId = (String) loginResp.getBody().get("challengeId");

        ResponseEntity<Map> mfaResp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", currentTotpCode())), Map.class);
        assertThat(mfaResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mfaResp.getBody()).containsKey("accessToken");
    }

    @Test
    void mfaVerifyWithWrongCodeFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "mfa2@a.com", "+919000000013", true);
        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        String challengeId = (String) loginResp.getBody().get("challengeId");

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", "000000")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void stepUpTokenIssuedForAuthenticatedCallerWithValidTotp() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "stepup@a.com", "+919000000014", true);
        String accessToken = mfaLoginAndGetAccessToken(tenant, staff);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<String> req = new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", headers);

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/mfa/verify"), req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("stepUpToken");
    }

    // ---- MFA enrollment + policy (NB-042) ----

    @Test
    void selfServiceEnrollConfirmIssuesRecoveryCodesAndEnablesMfa() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "enroll@a.com", "+919300000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> enrollResp = exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(token), Map.class);
        assertThat(enrollResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secretBase32 = (String) enrollResp.getBody().get("secretBase32");
        assertThat((String) enrollResp.getBody().get("otpauthUri")).startsWith("otpauth://totp/");

        ResponseEntity<Map> confirmResp = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(token, Map.of("code", totpCodeFor(secretBase32))), Map.class);
        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> codes = (List<String>) confirmResp.getBody().get("recoveryCodes");
        assertThat(codes).hasSize(8);
        assertThat(new java.util.HashSet<>(codes)).hasSize(8); // all distinct

        // MFA is now really enabled — the very next login gets a real challenge, not a bare token
        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(loginResp.getBody()).containsKey("challengeId");
    }

    @Test
    void mfaConfirmWithWrongCodeFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "enroll2@a.com", "+919300000002", false);
        String token = loginAndGetAccessToken(staff);

        exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(token), Map.class);
        ResponseEntity<Map> resp = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(token, Map.of("code", "000000")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void roleWithMfaRequiredForcesSetupBeforeAnySession() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Owner Policy", true, fullGrant("staff"));
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE roles SET mfa_required = true WHERE id = ?", roleId));
        SeededStaff staff = seedStaff(tenant, roleId, "policy@a.com", "+919300000003", false);

        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).containsKey("setupToken");
        assertThat(loginResp.getBody()).doesNotContainKeys("accessToken", "challengeId");
        String setupToken = (String) loginResp.getBody().get("setupToken");

        // the setup token authorizes enroll/confirm — nothing else
        ResponseEntity<Map> enrollResp = exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(setupToken), Map.class);
        assertThat(enrollResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secretBase32 = (String) enrollResp.getBody().get("secretBase32");
        ResponseEntity<Map> confirmResp = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(setupToken, Map.of("code", totpCodeFor(secretBase32))), Map.class);
        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // logging in again now goes through the normal MFA challenge, same as any other enrolled staff
        ResponseEntity<Map> secondLogin = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(secondLogin.getBody()).containsKey("challengeId");
        String challengeId = (String) secondLogin.getBody().get("challengeId");
        ResponseEntity<Map> mfaResp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", totpCodeFor(secretBase32))), Map.class);
        assertThat(mfaResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mfaResp.getBody()).containsKey("accessToken");
    }

    // ---- session revocation enforced immediately, not at token expiry (NB-043) ----

    @Test
    void revokedSessionIsDeniedOnTheVeryNextRequest() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "revoke@a.com", "+919300000004", false);
        String token = loginAndGetAccessToken(staff);

        // the token is still cryptographically valid and far from its 15-minute expiry
        ResponseEntity<List> before = exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), List.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID sessionId = currentSessionId(token);
        exchange("/v1/auth/sessions/" + sessionId, HttpMethod.DELETE, authed(token), Void.class);

        ResponseEntity<String> after = exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- PIN reset (NB-046) ----

    @Test
    void pinResetRequestThenConfirmChangesThePin() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String mobile = "+919300000005";
        SeededStaff staff = seedStaff(tenant, roleId, "reset@a.com", mobile, false);

        ResponseEntity<Void> reqResp = http.exchange(url("/v1/auth/pin/reset-request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", mobile)), Void.class);
        assertThat(reqResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String token = otpSender.sentTo.get(mobile);
        assertThat(token).isNotNull();

        ResponseEntity<Void> confirmResp = http.exchange(url("/v1/auth/pin/reset-confirm"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", mobile, "token", token, "newPin", "9999")), Void.class);
        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> oldPinLogin = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(oldPinLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> newPinLogin = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", "9999")), Map.class);
        assertThat(newPinLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void pinResetRequestForUnknownMobileStillReturns202NoEnumeration() {
        SeededTenant tenant = seedTenant();
        ResponseEntity<Void> resp = http.exchange(url("/v1/auth/pin/reset-request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", "+910000000099")), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(otpSender.sentTo).doesNotContainKey("+910000000099");
    }

    @Test
    void pinResetConfirmWithWrongTokenFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String mobile = "+919300000006";
        seedStaff(tenant, roleId, "reset2@a.com", mobile, false);

        ResponseEntity<Void> resp = http.exchange(url("/v1/auth/pin/reset-confirm"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", mobile, "token", "not-a-real-token", "newPin", "9999")), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- purpose-scoped tokens are never general bearer credentials (MF-01 / QA-01) ----

    /** Every bearer-authenticated endpoint the matrix below sends each intermediate token to. */
    private static final List<String[]> BEARER_ENDPOINTS = List.of(
            new String[]{"GET", "/v1/auth/sessions", null},
            new String[]{"POST", "/v1/auth/logout", null},
            new String[]{"DELETE", "/v1/auth/sessions/" + UUID.randomUUID(), null},
            new String[]{"POST", "/v1/auth/mfa/enroll", null},
            new String[]{"POST", "/v1/auth/mfa/confirm", "{\"code\":\"123456\"}"},
            new String[]{"POST", "/v1/auth/mfa/verify", "{\"challengeId\":\"x\",\"code\":\"123456\"}"},
            new String[]{"POST", "/v1/auth/unlock", "{\"pin\":\"" + STAFF_PIN + "\"}"},
            new String[]{"POST", "/v1/auth/break-glass/activate", "{\"reason\":\"x\"}"},
            new String[]{"POST", "/v1/auth/break-glass/" + UUID.randomUUID() + "/deactivate", null},
            new String[]{"POST", "/v1/auth/break-glass/" + UUID.randomUUID() + "/review", "{\"notes\":\"x\"}"},
            new String[]{"GET", "/v1/auth/break-glass/active", null},
            new String[]{"GET", "/v1/auth/break-glass/unreviewed", null},
            new String[]{"GET", "/v1/patients", null},
            new String[]{"GET", "/v1/staff", null},
            new String[]{"POST", "/v1/support/tickets", "{\"subject\":\"x\",\"description\":\"x\"}"},
            new String[]{"GET", "/v1/owners/me/workspaces", null},
            new String[]{"POST", "/v1/owners/workspaces/select", "{\"clinicId\":\"" + UUID.randomUUID() + "\"}"},
            new String[]{"GET", "/v1/platform/auth/me", null});

    /** The only (purpose, endpoint) pairs where an intermediate token is a valid bearer — tested positively elsewhere. */
    private static final Map<String, List<String>> ALLOWED_BEARER_USE = Map.of(
            "mfa_setup_required", List.of("/v1/auth/mfa/enroll", "/v1/auth/mfa/confirm"),
            "owner_pending_workspace", List.of("/v1/owners/me/workspaces", "/v1/owners/workspaces/select"));

    @Test
    void everyPurposeScopedTokenIsRejectedAsABearerEverywhereButItsOwnEndpoint() {
        SeededTenant tenant = seedTenant();
        UUID ownerRoleId = tenantOwnerRole(tenant);
        SeededStaff mfaStaff = seedStaff(tenant, ownerRoleId, "matrix-mfa@a.com", "+919310000001", true);
        UUID policyRole = seedRole(tenant.id(), "Matrix Policy", false, fullGrant("staff"), fullGrant("emergency"));
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE roles SET mfa_required = true WHERE id = ?", policyRole));
        SeededStaff policyStaff = seedStaff(tenant, policyRole, "matrix-policy@a.com", "+919310000002", false);
        SeededOperator operator = seedOperator("matrix-op@nabd.health", "super_admin", true);

        Map<String, String> tokens = new java.util.LinkedHashMap<>();
        tokens.put("mfa_pending", (String) login(tenant, mfaStaff).get("challengeId"));
        tokens.put("mfa_setup_required", (String) login(tenant, policyStaff).get("setupToken"));
        tokens.put("step_up", stepUpToken(mfaLoginAndGetAccessToken(tenant, mfaStaff)));
        tokens.put("owner_pending_workspace", ownerLoginPendingToken(seedOwner("matrix-owner@a.com")));
        tokens.put("platform_mfa_pending", (String) http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", operator.email(), "pin", STAFF_PIN)), Map.class).getBody().get("challengeId"));
        tokens.forEach((purpose, token) -> {
            assertThat(token).as(purpose).isNotNull();
            assertThat(decodeClaims(token).get("purpose")).isEqualTo(purpose);
        });

        List<String> accepted = new java.util.ArrayList<>();
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            for (String[] e : BEARER_ENDPOINTS) {
                if (ALLOWED_BEARER_USE.getOrDefault(t.getKey(), List.of()).contains(e[1])) {
                    continue;
                }
                ResponseEntity<String> resp = sendBearer(e[0], e[1], e[2], t.getValue());
                // rejected by the resource server itself (RFC 6750 invalid_token), not by some
                // later check that a different request shape might get past
                String challenge = resp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
                if (resp.getStatusCode() != HttpStatus.UNAUTHORIZED || challenge == null || !challenge.contains("invalid_token")) {
                    accepted.add(t.getKey() + " -> " + e[0] + " " + e[1] + " = " + resp.getStatusCode());
                }
            }
        }
        assertThat(accepted).isEmpty();

        // nothing was created on the way: no grant, no MFA secret swap
        Integer grants = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM break_glass_grants WHERE tenant_id = ?", Integer.class, tenant.id()));
        assertThat(grants).isZero();
        // ...and each token still works through the channel it was made for
        assertThat(http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", tokens.get("mfa_pending"), "code", currentTotpCode())), Map.class).getBody()).containsKey("accessToken");
    }

    @Test
    void mfaPendingTokenCannotActivateBreakGlass() {
        // QA-01's exact repro: PIN only, then break-glass with the challenge as a bearer
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, tenantOwnerRole(tenant), "qa01@a.com", "+919310000003", true);
        String challengeId = (String) login(tenant, staff).get("challengeId");

        ResponseEntity<Map> resp = exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBody(challengeId, Map.of("reason", "x")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNull(); // no token pair
    }

    @Test
    void challengeAndStepUpTokensAreNotInterchangeableInTheirOwnChannels() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, tenantOwnerRole(tenant), "swap@a.com", "+919310000004", true);
        String access = mfaLoginAndGetAccessToken(tenant, staff);
        String stepUp = stepUpToken(access);
        String challengeId = (String) login(tenant, staff).get("challengeId");

        // a step-up token is not a login challenge...
        assertThat(http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", stepUp, "code", currentTotpCode())), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // ...and a login challenge is not a step-up token
        UUID other = seedStaff(tenant, tenantOwnerRole(tenant), "swap-target@a.com", "+919310000005", false).id();
        ResponseEntity<Map> suspend = exchange("/v1/staff/" + other + "/suspend", HttpMethod.POST,
                authedJsonBodyWithHeader(access, "X-Step-Up-Token", challengeId, Map.of()), Map.class);
        assertThat(suspend.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suspend.getBody().get("type")).asString().contains("step-up-required");
    }

    // ---- MFA re-enrollment needs the current factor (QA-04) ----

    @Test
    void reenrollWithoutStepUpIsRefusedAndTheActiveSecretIsUntouched() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, tenantOwnerRole(tenant), "reenroll1@a.com", "+919310000006", true);
        String access = mfaLoginAndGetAccessToken(tenant, staff);

        ResponseEntity<Map> resp = exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(access), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().contains("step-up-required");

        // the old authenticator still works — QA-04's repro (enroll, log out, old code fails) no longer reproduces
        assertThat(mfaLoginAndGetAccessToken(tenant, staff)).isNotNull();
    }

    @Test
    void reenrollWithStepUpReplacesTheSecretOnlyOnConfirmAndRevokesOldRecoveryCodes() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, tenantOwnerRole(tenant), "reenroll2@a.com", "+919310000007", false);
        String plainToken = loginAndGetAccessToken(staff);
        String secret1 = (String) exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(plainToken), Map.class).getBody().get("secretBase32");
        List<String> oldCodes = (List<String>) exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(plainToken, Map.of("code", totpCodeFor(secret1))), Map.class).getBody().get("recoveryCodes");
        assertThat(oldCodes).hasSize(8);

        String access = mfaLoginWithSecret(tenant, staff, secret1);
        String stepUp = stepUpTokenWithSecret(access, secret1);
        ResponseEntity<Map> enroll = exchange("/v1/auth/mfa/enroll", HttpMethod.POST,
                authedJsonBodyWithHeader(access, "X-Step-Up-Token", stepUp, Map.of()), Map.class);
        assertThat(enroll.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secret2 = (String) enroll.getBody().get("secretBase32");

        // abandoned at this point, nothing would change: the old secret still signs in
        assertThat(mfaLoginWithSecret(tenant, staff, secret1)).isNotNull();

        ResponseEntity<Map> confirm = exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(access, Map.of("code", totpCodeFor(secret2))), Map.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) confirm.getBody().get("recoveryCodes")).hasSize(8).doesNotContainAnyElementsOf(oldCodes);

        // now the new secret is the only one that works
        String challengeId = (String) login(tenant, staff).get("challengeId");
        assertThat(http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", totpCodeFor(secret1))), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mfaLoginWithSecret(tenant, staff, secret2)).isNotNull();

        Map<String, Object> counts = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE revoked_at IS NOT NULL) AS revoked, COUNT(*) FILTER (WHERE revoked_at IS NULL) AS live " +
                        "FROM mfa_recovery_codes WHERE staff_id = ?", staff.id()));
        assertThat(((Number) counts.get("revoked")).intValue()).isEqualTo(8);
        assertThat(((Number) counts.get("live")).intValue()).isEqualTo(8);
        Integer audits = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'auth.mfa_reenrolled'", Integer.class, staff.id()));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void setupTokenCannotReenrollOnceMfaIsOn() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Setup Policy", false, fullGrant("staff"));
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE roles SET mfa_required = true WHERE id = ?", roleId));
        SeededStaff staff = seedStaff(tenant, roleId, "setup-reuse@a.com", "+919310000008", false);
        String setupToken = (String) login(tenant, staff).get("setupToken");

        String secret = (String) exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(setupToken), Map.class).getBody().get("secretBase32");
        assertThat(exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(setupToken, Map.of("code", totpCodeFor(secret))), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // the same (still unexpired) setup token must not be able to swap the factor it just set up
        ResponseEntity<Map> again = exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authed(setupToken), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(again.getBody().get("type")).asString().contains("session-required");
    }

    @Test
    void confirmWithoutAPendingEnrollmentFails() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, tenantOwnerRole(tenant), "nopending@a.com", "+919310000009", true);
        String access = mfaLoginAndGetAccessToken(tenant, staff);
        // a code from the ACTIVE secret proves nothing about a new one — there is no new one
        assertThat(exchange("/v1/auth/mfa/confirm", HttpMethod.POST,
                authedJsonBody(access, Map.of("code", currentTotpCode())), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- break-glass (NB-048, MF-02 / QA-02 / QA-26) ----

    @Test
    void breakGlassIsDeniedWithoutTheEmergencyPermission() {
        SeededTenant tenant = seedTenant();
        tenantOwnerRole(tenant);
        UUID receptionist = seedRole(tenant.id(), "Receptionist", false,
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff staff = seedStaff(tenant, receptionist, "bg-noperm@a.com", "+919310000010", true);
        String access = mfaLoginAndGetAccessToken(tenant, staff);

        ResponseEntity<Map> resp = exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBodyWithHeader(access, "X-Step-Up-Token", stepUpToken(access), Map.of("reason", "emergency")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(activeGrantCount(tenant)).isZero();
    }

    @Test
    void breakGlassRequiresAStepUpToken() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, clinicianRole(tenant), "bg-nostep@a.com", "+919310000011", true);
        String access = mfaLoginAndGetAccessToken(tenant, staff);

        ResponseEntity<Map> resp = exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBody(access, Map.of("reason", "emergency")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().contains("step-up-required");
        assertThat(activeGrantCount(tenant)).isZero();
    }

    @Test
    void breakGlassGrantsOnlyTheClinicalEmergencySetToTheNewSessionAndExpiryIsAudited() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, clinicianRole(tenant), "bg@a.com", "+919310000012", true);
        String access = mfaLoginAndGetAccessToken(tenant, staff);
        assertThat(permissionsOf(access)).doesNotContain("clinical:edit");

        Map<?, ?> elevated = activateBreakGlass(access, "Patient emergency, need chart access now");
        String elevatedToken = (String) elevated.get("accessToken");
        assertThat(permissionsOf(elevatedToken)).containsAll(AuthService.BREAK_GLASS_PERMISSIONS)
                .doesNotContain("staff:edit", "staff:delete", "billing:approve", "setup:edit", "emergency:approve", "reports:view");
        // the elevated access token can't outlive the 30-minute grant
        long exp = ((Number) decodeClaims(elevatedToken).get("exp")).longValue();
        assertThat(exp).isLessThanOrEqualTo(java.time.Instant.now().plusSeconds(30 * 60 + 5).getEpochSecond());

        // only the session minted at activation is elevated — not the one it was activated from, not a fresh login
        assertThat(permissionsOf(mfaLoginAndGetAccessToken(tenant, staff))).doesNotContain("clinical:edit");
        // refreshing the elevated session keeps the elevation while the grant lasts
        Map<?, ?> refreshed = refreshPair((String) elevated.get("refreshToken"));
        assertThat(permissionsOf((String) refreshed.get("accessToken"))).contains("clinical:edit");

        UUID grantId = onlyGrantId(tenant);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE break_glass_grants SET expires_at = now() - interval '1 minute' WHERE id = ?", grantId));
        Map<?, ?> afterExpiry = refreshPair((String) refreshed.get("refreshToken"));
        assertThat(permissionsOf((String) afterExpiry.get("accessToken"))).doesNotContain("clinical:edit");

        Integer auditRows = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'break_glass_grant' AND entity_id = ? AND action IN ('auth.break_glass_activated','auth.break_glass_expired')",
                Integer.class, grantId));
        assertThat(auditRows).isEqualTo(2);
    }

    @Test
    void holderCanEndTheirOwnGrantAndTheElevatedSessionDiesImmediately() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, clinicianRole(tenant), "bg2@a.com", "+919310000013", true);
        String access = mfaLoginAndGetAccessToken(tenant, staff);
        String elevatedToken = (String) activateBreakGlass(access, "testing").get("accessToken");
        UUID grantId = onlyGrantId(tenant);

        // a colleague without emergency:approve can't end it (or learn it exists)
        SeededStaff colleague = seedStaff(tenant, clinicianRole(tenant), "bg2-colleague@a.com", "+919310000014", false);
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST,
                authed(loginAndGetAccessToken(colleague)), Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Void> deactivate = exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST,
                authed(access), Void.class);
        assertThat(deactivate.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(elevatedToken), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(access), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK); // the session it was activated from carries on
        assertThat(activeGrantCount(tenant)).isZero();
    }

    @Test
    void ownerCanEndSomeoneElsesGrant() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, clinicianRole(tenant), "bg3@a.com", "+919310000015", true);
        String elevatedToken = (String) activateBreakGlass(mfaLoginAndGetAccessToken(tenant, staff), "testing").get("accessToken");
        UUID grantId = onlyGrantId(tenant);

        String ownerToken = loginAndGetAccessToken(seedStaff(tenant, tenantOwnerRole(tenant), "bg3-owner@a.com", "+919310000016", false));
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST, authed(ownerToken), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(elevatedToken), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void everyGrantIsQueuedForReviewBySomeoneOtherThanTheActivator() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = tenantOwnerRole(tenant);
        SeededStaff activator = seedStaff(tenant, ownerRole, "bg4@a.com", "+919310000017", true);
        String activatorToken = mfaLoginAndGetAccessToken(tenant, activator);
        activateBreakGlass(activatorToken, "night shift, no doctor on site");
        UUID grantId = onlyGrantId(tenant);

        String reviewerToken = loginAndGetAccessToken(seedStaff(tenant, ownerRole, "bg4-reviewer@a.com", "+919310000018", false));
        ResponseEntity<List> queue = exchange("/v1/auth/break-glass/unreviewed", HttpMethod.GET, authed(reviewerToken), List.class);
        assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queue.getBody()).extracting(g -> ((Map<?, ?>) g).get("id")).containsExactly(grantId.toString());

        // no marking your own homework
        ResponseEntity<Map> self = exchange("/v1/auth/break-glass/" + grantId + "/review", HttpMethod.POST,
                authedJsonBody(activatorToken, Map.of("notes", "all fine")), Map.class);
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(self.getBody().get("type")).asString().contains("self-review");

        // reviewing needs emergency:approve
        String clinicianToken = loginAndGetAccessToken(seedStaff(tenant, clinicianRole(tenant), "bg4-clin@a.com", "+919310000019", false));
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/review", HttpMethod.POST,
                authedJsonBody(clinicianToken, Map.of("notes", "x")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/review", HttpMethod.POST,
                authedJsonBody(reviewerToken, Map.of("notes", "Checked chart access log, justified")), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/review", HttpMethod.POST,
                authedJsonBody(reviewerToken, Map.of("notes", "again")), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange("/v1/auth/break-glass/unreviewed", HttpMethod.GET, authed(reviewerToken), List.class).getBody()).isEmpty();
        Integer audits = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'auth.break_glass_reviewed'", Integer.class, grantId));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void cannotDelegateARoleToYourself() {
        // QA-03: self-delegation is what stretched a 30-minute elevation into 30 days
        SeededTenant tenant = seedTenant();
        UUID ownerRole = tenantOwnerRole(tenant);
        SeededStaff owner = seedStaff(tenant, ownerRole, "selfdeleg@a.com", "+919310000020", false);
        ResponseEntity<Map> resp = exchange("/v1/roles/delegations", HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(owner), Map.of(
                "staffId", owner.id().toString(), "delegatedRoleId", ownerRole.toString(),
                "reason", "cover", "expiresAt", java.time.Instant.now().plusSeconds(3600).toString())), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("self-delegation");
    }

    // ---- helpers ----

    /** The tenant's built-in Owner role, seeded on first use. */
    private UUID tenantOwnerRole(SeededTenant tenant) {
        List<UUID> existing = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT id FROM roles WHERE tenant_id = ? AND built_in = true", UUID.class, tenant.id()));
        return existing.isEmpty() ? seedFullAccessRole(tenant.id()) : existing.get(0);
    }

    /** A clinic role that may use break-glass but holds no clinical rights of its own. */
    private UUID clinicianRole(SeededTenant tenant) {
        return seedRole(tenant.id(), "Clinician " + UUID.randomUUID(), false,
                new ModuleGrant("queue", true, false, false, false, false, false, false),
                new ModuleGrant("emergency", false, true, false, false, false, false, false));
    }

    private Map<?, ?> activateBreakGlass(String accessToken, String reason) {
        ResponseEntity<Map> resp = exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBodyWithHeader(accessToken, "X-Step-Up-Token", stepUpToken(accessToken), Map.of("reason", reason)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private UUID onlyGrantId(SeededTenant tenant) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT id FROM break_glass_grants WHERE tenant_id = ?", UUID.class, tenant.id()));
    }

    private int activeGrantCount(SeededTenant tenant) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM break_glass_grants WHERE tenant_id = ? AND deactivated_at IS NULL", Integer.class, tenant.id()));
    }

    private Map<?, ?> refreshPair(String refreshToken) {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", refreshToken)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private String stepUpToken(String accessToken) {
        return stepUpTokenWithCode(accessToken, currentTotpCode());
    }

    private String stepUpTokenWithSecret(String accessToken, String secretBase32) {
        return stepUpTokenWithCode(accessToken, totpCodeFor(secretBase32));
    }

    private String stepUpTokenWithCode(String accessToken, String code) {
        ResponseEntity<Map> resp = exchange("/v1/auth/mfa/verify", HttpMethod.POST,
                authedJsonBody(accessToken, Map.of("challengeId", "unused", "code", code)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("stepUpToken");
    }

    private String mfaLoginWithSecret(SeededTenant tenant, SeededStaff staff, String secretBase32) {
        String challengeId = (String) login(tenant, staff).get("challengeId");
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", totpCodeFor(secretBase32))), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("accessToken");
    }

    private ResponseEntity<String> sendBearer(String method, String path, String jsonBody, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange(url(path), HttpMethod.valueOf(method), new HttpEntity<>(jsonBody, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> permissionsOf(String accessToken) {
        return (List<String>) decodeClaims(accessToken).get("permissions");
    }

    private Map<String, Object> decodeClaims(String accessToken) {
        String[] parts = accessToken.split("\\.");
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String totpCodeFor(String secretBase32) {
        byte[] secret = org.bouncycastle.util.encoders.Base32.decode(secretBase32);
        long counter = java.time.Instant.now().getEpochSecond() / 30;
        return new TotpService().generate(secret, counter);
    }

    private Map<?, ?> login(SeededTenant tenant, SeededStaff staff) {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        return resp.getBody();
    }

    private String mfaLoginAndGetAccessToken(SeededTenant tenant, SeededStaff staff) {
        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        String challengeId = (String) loginResp.getBody().get("challengeId");
        ResponseEntity<Map> mfaResp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", currentTotpCode())), Map.class);
        return (String) mfaResp.getBody().get("accessToken");
    }

    private UUID currentSessionId(String accessToken) {
        String[] parts = accessToken.split("\\.");
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
        try {
            Map<?, ?> claims = objectMapper.readValue(payload, Map.class);
            return UUID.fromString((String) claims.get("sid"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class OtpCaptorConfig {
        @Bean
        @Primary
        CapturingOtpSender captor() {
            return new CapturingOtpSender();
        }
    }

    static class CapturingOtpSender implements WhatsAppOtpSender {
        final Map<String, String> sentTo = new ConcurrentHashMap<>();

        @Override
        public void send(String mobilePhone, String code) {
            sentTo.put(mobilePhone, code);
        }
    }
}
