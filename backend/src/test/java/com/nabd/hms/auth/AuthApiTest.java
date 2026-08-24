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
    void otpRequestForUnknownMobileStillReturns202NoEnumeration() {
        SeededTenant tenant = seedTenant();
        ResponseEntity<Void> resp = http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", "+910000000000")), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(otpSender.sentTo).doesNotContainKey("+910000000000");
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

    // ---- break-glass (NB-048) ----

    @Test
    void breakGlassGrantsOwnerPermissionsImmediatelyAndExpiryIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID ownerRoleId = seedFullAccessRole(tenant.id()); // built_in Owner — break-glass elevates to this
        UUID limitedRoleId = seedRole(tenant.id(), "Receptionist", false,
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff staff = seedStaff(tenant, limitedRoleId, "bg@a.com", "+919300000007", false);
        String token = loginAndGetAccessToken(staff);
        assertThat(permissionsOf(token)).doesNotContain("staff:delete");

        ResponseEntity<Map> activateResp = exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "Patient emergency, need chart access now")), Map.class);
        assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String elevatedToken = (String) activateResp.getBody().get("accessToken");
        assertThat(permissionsOf(elevatedToken)).contains("staff:delete"); // full Owner grant, immediately

        // find the grant id via the owner-visible panel, then force it into the past
        String ownerToken = loginAndGetAccessToken(seedStaff(tenant, ownerRoleId, "owner8@a.com", "+919300000008", false));
        ResponseEntity<List> activeResp = exchange("/v1/auth/break-glass/active", HttpMethod.GET, authed(ownerToken), List.class);
        assertThat(activeResp.getBody()).hasSize(1);
        String grantId = (String) ((Map<?, ?>) activeResp.getBody().get(0)).get("id");

        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE break_glass_grants SET expires_at = now() - interval '1 minute' WHERE id = ?",
                UUID.fromString(grantId)));
        String tokenAfterExpiry = loginAndGetAccessToken(staff);
        assertThat(permissionsOf(tokenAfterExpiry)).doesNotContain("staff:delete");

        Integer auditRows = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'break_glass_grant' AND entity_id = ? AND action IN ('auth.break_glass_activated','auth.break_glass_expired')",
                Integer.class, UUID.fromString(grantId)));
        assertThat(auditRows).isEqualTo(2);
    }

    @Test
    void breakGlassCanBeDeactivatedManually() {
        SeededTenant tenant = seedTenant();
        UUID limitedRoleId = seedRole(tenant.id(), "Receptionist 2", false,
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        seedFullAccessRole(tenant.id()); // ensures a built-in Owner role exists to elevate to
        SeededStaff staff = seedStaff(tenant, limitedRoleId, "bg2@a.com", "+919300000009", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> activateResp = exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "testing")), Map.class);
        String elevatedToken = (String) activateResp.getBody().get("accessToken");

        Map<?, ?> claims = decodeClaims(elevatedToken);
        // grantId isn't in the token; look it up the same way the owner panel would
        ResponseEntity<List> activeResp = exchange("/v1/auth/break-glass/active", HttpMethod.GET, authed(elevatedToken), List.class);
        String grantId = (String) ((Map<?, ?>) activeResp.getBody().get(0)).get("id");

        ResponseEntity<Void> deactivateResp = exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST,
                authed(elevatedToken), Void.class);
        assertThat(deactivateResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String tokenAfter = loginAndGetAccessToken(staff);
        assertThat(permissionsOf(tokenAfter)).doesNotContain("staff:delete");
    }

    // ---- helpers ----

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
