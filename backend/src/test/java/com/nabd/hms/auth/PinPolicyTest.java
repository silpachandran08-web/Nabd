package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PinPolicyTest extends Plt003Support {

    private ResponseEntity<String> change(String token, String current, String next) {
        return exchange("/v1/auth/pin/change", HttpMethod.POST, authedJsonBody(token, Map.of("currentPin", current, "newPin", next)), String.class);
    }

    @Test
    void legacyPinMustBeChangedBeforeAnyOtherCallThenWorksAfterRefresh() {
        SeededStaff s = newStaff("legacy@a.com", "+919300000041", false);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET pin_policy_version = 1 WHERE id = ?", s.id()));

        ResponseEntity<Map> loginResp = login(s, STAFF_PIN); // sign-in itself still succeeds
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResp.getBody().get("accessToken");
        String refresh = (String) loginResp.getBody().get("refreshToken");

        ResponseEntity<Map> blocked = exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(blocked.getBody().get("type"))).endsWith("pin-change-required");

        assertThat(change(token, STAFF_PIN, "12345").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);   // 5 digits
        assertThat(change(token, STAFF_PIN, "123456789").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // 9 digits
        assertThat(change(token, "000000", "482916").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(change(token, STAFF_PIN, "482916").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> refreshed = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", refresh)), Map.class);
        String fresh = (String) refreshed.getBody().get("accessToken");
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(fresh), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(s, "482916").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(s, STAFF_PIN).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pinChangeWrongCurrentPinsCountTowardTheLockout() {
        SeededStaff s = newStaff("pinchg-lock@a.com", "+919300000042", false);
        String token = loginAndGetAccessToken(s);
        for (int i = 0; i < 5; i++) {
            assertThat(change(token, "000000", "482916").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(change(token, STAFF_PIN, "482916").getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void pinResetAndLoginBoundaries() {
        SeededStaff s = newStaff("reset-short@a.com", "+919300000043", false);
        ResponseEntity<Map> shortReset = http.postForEntity(url("/v1/auth/pin/reset-confirm"), jsonBody(Map.of(
                "tenantSlug", s.slug(), "mobilePhone", "+919300000043", "token", "x", "newPin", "12345")), Map.class);
        assertThat(shortReset.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 8-digit login accepted by validation (wrong pin => 401, not 400); 9 digits rejected
        assertThat(login(s, "00000000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(s, "000000000").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
