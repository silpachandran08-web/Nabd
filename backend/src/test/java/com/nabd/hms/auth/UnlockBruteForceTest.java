package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnlockBruteForceTest extends Plt003Support {

    private ResponseEntity<Map> unlock(String token, String pin) {
        return exchange("/v1/auth/unlock", HttpMethod.POST, authedJsonBody(token, Map.of("pin", pin)), Map.class);
    }

    @Test
    void fiveWrongPinsLockAndTheSixthIsRefusedWithoutCheckingThePin() {
        SeededStaff s = newStaff("unlock-lock@a.com", "+919300000021", false);
        String token = loginAndGetAccessToken(s);

        for (int i = 0; i < 5; i++) {
            assertThat(unlock(token, "000000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Map> sixth = unlock(token, STAFF_PIN); // the CORRECT pin: must still be refused
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(((Number) sixth.getBody().get("retryAfterSeconds")).longValue()).isPositive();
        assertThat(failedAttempts(s.id(), "unlock")).isEqualTo(5); // 6th recorded nothing
        assertThat(login(s, STAFF_PIN).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void fourWrongPinsThenTheRightOneStillUnlocksAndResetsTheCount() {
        SeededStaff s = newStaff("unlock-ok@a.com", "+919300000022", false);
        String token = loginAndGetAccessToken(s);
        for (int i = 0; i < 4; i++) {
            unlock(token, "000000");
        }
        assertThat(unlock(token, STAFF_PIN).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        for (int i = 0; i < 4; i++) {
            assertThat(unlock(token, "000000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
