package com.nabd.hms.auth.internal.token;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-034: the access token minted by TokenService, step-up via StepUpTokenService, and the AuthErrors problem shapes. */
class TokenServiceApiTest extends ApiTestBase {

    @Test
    void accessTokenCarriesTenantSessionAndPermissions() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "token@plt034.test", "+919000034041", false);
        String jwt = loginAndGetAccessToken(s);
        Map<String, Object> claims = objectMapper.readValue(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8), Map.class);
        assertThat(claims.get("tenantId")).isEqualTo(t.id().toString());
        assertThat(claims.get("sub")).isEqualTo(s.id().toString());
        assertThat(claims).containsKeys("sid", "permissions", "roleName", "pinChange");
    }

    @Test
    void stepUpWithoutAnEnrolledAuthenticatorIsMfaFailed() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "stepup@plt034.test", "+919000034042", false);
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/mfa/verify"), authedJsonBody(loginAndGetAccessToken(s), Map.of("challengeId", "", "code", "123456")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody().get("type")).asString().endsWith("mfa-failed");
    }

    @Test
    void authErrorsKeepTheirProblemTypes() {
        assertThat(AuthErrors.invalidCredentials().typeSlug()).isEqualTo("invalid-credentials");
        assertThat(AuthErrors.invalidRefresh().typeSlug()).isEqualTo("invalid-refresh-token");
        assertThat(AuthErrors.sessionMaxAge().typeSlug()).isEqualTo("SESSION_MAX_AGE");
        assertThat(AuthErrors.tenantInactive(HttpStatus.FORBIDDEN).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(AuthErrors.challengeInvalid().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
