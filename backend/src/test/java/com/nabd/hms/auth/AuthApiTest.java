package com.nabd.hms.auth;

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

    // ---- helpers ----

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
