package com.nabd.hms.auth.internal.session;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-034: refresh rotation, reuse detection and own-device revoke through the split SessionService. */
class SessionServiceApiTest extends ApiTestBase {

    @Test
    void refreshRotatesAndReplayingTheOldTokenRevokesTheFamily() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "session@plt034.test", "+919000034031", false);
        Map<?, ?> first = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class).getBody();
        String r1 = (String) first.get("refreshToken");

        ResponseEntity<Map> rotated = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", r1)), Map.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String r2 = (String) rotated.getBody().get("refreshToken");

        ResponseEntity<Map> replay = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", r1)), Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody().get("type")).asString().endsWith("invalid-refresh-token");
        // the reuse revoked the whole family, so the rotated sibling is dead too
        assertThat(http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", r2)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void revokingAnUnknownSessionIsTheShared404() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "revoke@plt034.test", "+919000034032", false);
        ResponseEntity<Map> r = exchange("/v1/auth/sessions/" + UUID.randomUUID(), HttpMethod.DELETE, authed(loginAndGetAccessToken(s)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("type")).asString().endsWith("not-found");
        assertThat(r.getBody().get("detail")).isEqualTo("The requested resource was not found.");
    }
}
