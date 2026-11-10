package com.nabd.hms.auth.internal.mfa;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-034: the TOTP second factor and recovery codes through the split MfaService. */
class MfaServiceApiTest extends ApiTestBase {

    private String challenge(SeededStaff s) {
        return (String) http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class)
                .getBody().get("challengeId");
    }

    @Test
    void rightCodeMintsASessionWrongCodeIsMfaFailed() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "mfa@plt034.test", "+919000034021", true);

        ResponseEntity<Map> bad = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", challenge(s), "code", "000000")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody().get("type")).asString().endsWith("mfa-failed");

        ResponseEntity<Map> ok = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", challenge(s), "code", currentTotpCode())), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsKeys("accessToken", "refreshToken");
    }

    @Test
    void tamperedChallengeIsMfaFailedAndRecoveryWithOneIsChallengeInvalid() {
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", "not.a.jwt", "code", "123456")), Map.class);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(v.getBody().get("type")).asString().endsWith("mfa-failed");
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/mfa/recovery-verify"),
                jsonBody(Map.of("challengeToken", "not.a.jwt", "recoveryCode", "ABCDEFGHIJ")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody().get("type")).asString().endsWith("challenge-invalid");
    }
}
