package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TotpLockoutTest extends Plt003Support {

    @Test
    void fiveWrongCodesAcrossFreshChallengesLockTheAccountAndRefuseTheCorrectCode() {
        SeededStaff s = newStaff("totp-lock@a.com", "+919300000001", true);
        String firstChallenge = challenge(s);

        for (int i = 0; i < 5; i++) {
            assertThat(verify(challenge(s), "000000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(failedAttempts(s.id(), "totp")).isEqualTo(5);

        ResponseEntity<Map> correct = verify(firstChallenge, totpCodeAtOffset(0));
        assertThat(correct.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(((Number) correct.getBody().get("retryAfterSeconds")).longValue()).isBetween(1L, 60L); // 1 min first backoff
        assertThat(login(s, STAFF_PIN).getStatusCode()).isEqualTo(HttpStatus.LOCKED); // PIN login shares the lock

        // a locked hit records nothing, so the window cannot be renewed by polling
        assertThat(failedAttempts(s.id(), "totp")).isEqualTo(5);
    }

    @Test
    void fourWrongCodesDoNotLockAndTheLockExpires() {
        SeededStaff s = newStaff("totp-boundary@a.com", "+919300000002", true);
        for (int i = 0; i < 4; i++) {
            verify(challenge(s), "000000");
        }
        assertThat(verify(challenge(s), totpCodeAtOffset(0)).getStatusCode()).isEqualTo(HttpStatus.OK); // 4 < threshold

        SeededStaff t = newStaff("totp-expire@a.com", "+919300000003", true);
        String c = challenge(t);
        for (int i = 0; i < 5; i++) {
            verify(challenge(t), "000000");
        }
        assertThat(verify(c, totpCodeAtOffset(0)).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        jdbc.update("UPDATE login_attempts SET attempted_at = now() - interval '2 minutes' WHERE staff_id = ?", t.id());
        assertThat(verify(c, totpCodeAtOffset(0)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void pinLoginsBetweenGuessesDoNotResetTheCounter() {
        SeededStaff s = newStaff("totp-nopinreset@a.com", "+919300000004", true);
        for (int i = 0; i < 5; i++) {
            verify(challenge(s), "000000"); // each challenge comes from a correct-PIN login
        }
        assertThat(login(s, STAFF_PIN).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }
}
