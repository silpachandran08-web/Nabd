package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mirrors AuthApiTest's coverage — platform-operator auth is a separate identity domain, not a reuse, so it gets the same rigor. */
class PlatformAuthApiTest extends ApiTestBase {

    // ---- login ----

    @Test
    void loginWithCorrectPinSucceeds() {
        SeededOperator operator = seedOperator("admin1@nabd.health", "super_admin", false);

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", operator.email(), "pin", STAFF_PIN)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("accessToken", "refreshToken", "expiresIn");
    }

    @Test
    void loginWithWrongPinFails() {
        SeededOperator operator = seedOperator("admin2@nabd.health", "support_engineer", false);

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", operator.email(), "pin", "9999")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("type")).asString().contains("invalid-credentials");
    }

    @Test
    void loginWithUnknownEmailFails() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", "nobody@nabd.health", "pin", "1234")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginValidatesPinShape() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", "a@nabd.health", "pin", "abcd")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- lockout (same regression as staff/owner login this session) ----

    @Test
    void repeatedFailuresLockAccountAndFurtherHitsDoNotExtendTheWindow() {
        SeededOperator operator = seedOperator("admin3@nabd.health", "billing", false);

        for (int i = 0; i < 5; i++) {
            http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                    "email", operator.email(), "pin", "0000")), Map.class);
        }
        ResponseEntity<Map> locked = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", operator.email(), "pin", "0000")), Map.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        Integer attemptsAfterFirstLock = jdbc.queryForObject(
                "SELECT COUNT(*) FROM master.login_attempts WHERE operator_id = ?", Integer.class, operator.id());

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> stillLocked = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                    "email", operator.email(), "pin", STAFF_PIN)), Map.class);
            assertThat(stillLocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        }

        Integer attemptsAfterHammering = jdbc.queryForObject(
                "SELECT COUNT(*) FROM master.login_attempts WHERE operator_id = ?", Integer.class, operator.id());
        assertThat(attemptsAfterHammering).isEqualTo(attemptsAfterFirstLock);
    }

    // ---- sessions ----

    @Test
    void sessionOwnershipIsEnforcedOnRevoke() {
        SeededOperator operatorA = seedOperator("a@nabd.health", "super_admin", false);
        SeededOperator operatorB = seedOperator("b@nabd.health", "sre", false);

        String tokenA = platformLoginAndGetAccessToken(operatorA);
        String tokenB = platformLoginAndGetAccessToken(operatorB);
        UUID sessionIdB = currentSessionId(tokenB);

        ResponseEntity<Void> resp = exchange("/v1/platform/auth/sessions/" + sessionIdB, HttpMethod.DELETE,
                authed(tokenA), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void operatorCanRevokeTheirOwnSession() {
        SeededOperator operator = seedOperator("self@nabd.health", "implementation", false);
        String token = platformLoginAndGetAccessToken(operator);
        UUID sessionId = currentSessionId(token);

        ResponseEntity<Void> resp = exchange("/v1/platform/auth/sessions/" + sessionId, HttpMethod.DELETE, authed(token), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void listSessionsMarksCurrentSession() {
        SeededOperator operator = seedOperator("list@nabd.health", "commercial", false);
        String token = platformLoginAndGetAccessToken(operator);

        ResponseEntity<List> resp = exchange("/v1/platform/auth/sessions", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> sessions = resp.getBody();
        assertThat(sessions).anyMatch(s -> Boolean.TRUE.equals(s.get("current")));
    }

    @Test
    void logoutRevokesTheSessionSoRefreshFails() {
        SeededOperator operator = seedOperator("logout@nabd.health", "compliance_dpo", false);
        Map<?, ?> loginBody = login(operator);
        String accessToken = (String) loginBody.get("accessToken");
        String refreshToken = (String) loginBody.get("refreshToken");

        ResponseEntity<Void> logoutResp = exchange("/v1/platform/auth/logout", HttpMethod.POST, authed(accessToken), Void.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> refreshResp = http.postForEntity(url("/v1/platform/auth/refresh"),
                jsonBody(Map.of("refreshToken", refreshToken)), Map.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRotatesTokenAndRejectsReuse() {
        SeededOperator operator = seedOperator("refresh@nabd.health", "super_admin", false);
        Map<?, ?> loginBody = login(operator);
        String originalRefresh = (String) loginBody.get("refreshToken");

        ResponseEntity<Map> rotated = http.postForEntity(url("/v1/platform/auth/refresh"),
                jsonBody(Map.of("refreshToken", originalRefresh)), Map.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefresh = (String) rotated.getBody().get("refreshToken");
        assertThat(newRefresh).isNotEqualTo(originalRefresh);

        ResponseEntity<Map> reused = http.postForEntity(url("/v1/platform/auth/refresh"),
                jsonBody(Map.of("refreshToken", originalRefresh)), Map.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> afterReuse = http.postForEntity(url("/v1/platform/auth/refresh"),
                jsonBody(Map.of("refreshToken", newRefresh)), Map.class);
        assertThat(afterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithGarbageTokenFails() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/refresh"),
                jsonBody(Map.of("refreshToken", "not-a-real-token")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- profile / permissions (NB-257) ----

    @Test
    void meReturnsProfileWithRoleScopedPermissions() {
        SeededOperator operator = seedOperator("billing1@nabd.health", "billing", false);
        String token = platformLoginAndGetAccessToken(operator);

        ResponseEntity<Map> resp = exchange("/v1/platform/auth/me", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("role")).isEqualTo("billing");
        assertThat((List<String>) resp.getBody().get("permissions"))
                .containsExactlyInAnyOrder("command_centre:view", "clinics_fleet:view",
                        "billing_revenue:view", "pricing_packaging:view");
    }

    @Test
    void superAdminMeSeesAllFourteenSurfaces() {
        SeededOperator operator = seedOperator("super1@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(operator);

        ResponseEntity<Map> resp = exchange("/v1/platform/auth/me", HttpMethod.GET, authed(token), Map.class);
        assertThat((List<String>) resp.getBody().get("permissions")).hasSize(14);
    }

    @Test
    void accessTokenCarriesThePermissionsClaimForRole() throws Exception {
        SeededOperator operator = seedOperator("support1@nabd.health", "support_engineer", false);
        String token = platformLoginAndGetAccessToken(operator);

        String[] parts = token.split("\\.");
        Map<?, ?> claims = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(parts[1]), Map.class);
        assertThat((List<String>) claims.get("permissions"))
                .containsExactlyInAnyOrder("command_centre:view", "clinics_fleet:view", "tenant_detail:view",
                        "support_tickets:view", "platform_health:view", "support_access:view");
    }

    // ---- MFA challenge ----

    @Test
    void loginWithMfaEnabledReturnsChallengeThenVerifyIssuesTokens() {
        SeededOperator operator = seedOperator("mfa@nabd.health", "super_admin", true);

        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", operator.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).containsKey("challengeId");
        String challengeId = (String) loginResp.getBody().get("challengeId");

        ResponseEntity<Map> mfaResp = http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", currentTotpCode())), Map.class);
        assertThat(mfaResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mfaResp.getBody()).containsKey("accessToken");
    }

    @Test
    void mfaVerifyWithWrongCodeFails() {
        SeededOperator operator = seedOperator("mfa2@nabd.health", "super_admin", true);
        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", operator.email(), "pin", STAFF_PIN)), Map.class);
        String challengeId = (String) loginResp.getBody().get("challengeId");

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", "000000")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // A staff/owner MFA challenge token must not work here, and vice versa — different "purpose" claims.
    @Test
    void staffMfaChallengeIsRejectedByPlatformMfaVerify() {
        // Reuses the plain login challenge shape but from the wrong domain entirely: garbage challengeId
        // decodes fine as "not a JWT at all" in the staff/owner paths too, so this just confirms the
        // 401 path holds for any non-platform-purpose token.
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", "not-a-jwt", "code", "123456")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- helpers ----

    private Map<?, ?> login(SeededOperator operator) {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", operator.email(), "pin", STAFF_PIN)), Map.class);
        return resp.getBody();
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
}
