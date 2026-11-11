package com.nabd.hms.auth.internal.login;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-034: email + PIN sign-in through the split LoginService (same responses as before the split). */
class LoginServiceApiTest extends ApiTestBase {

    private ResponseEntity<Map> login(SeededStaff s, String pin) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", pin)), Map.class);
    }

    @Test
    void correctPinIssuesTokensAndWrongPinIsRecordedAsAFailedAttempt() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "login@plt034.test", "+919000034001", false);

        assertThat(login(s, STAFF_PIN).getBody()).containsKeys("accessToken", "refreshToken", "expiresIn");

        ResponseEntity<Map> bad = login(s, "9999");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody().get("type")).asString().endsWith("invalid-credentials");
        Integer failed = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM login_attempts WHERE staff_id = ? AND NOT succeeded", Integer.class, s.id()));
        assertThat(failed).isEqualTo(1); // noRollbackFor kept the lockout counter's write
    }

    @Test
    void mfaEnabledStaffGetsAChallengeNotASession() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "mfa-login@plt034.test", "+919000034002", true);
        ResponseEntity<Map> r = login(s, STAFF_PIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("challengeId").doesNotContainKey("accessToken");
    }

    @Test
    void unknownClinicIsTheSameGeneric401() {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"),
                jsonBody(Map.of("tenantSlug", "no-such-clinic-plt034", "email", "x@plt034.test", "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody().get("type")).asString().endsWith("invalid-credentials");
    }
}
