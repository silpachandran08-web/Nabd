package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class TotpReplayTest extends Plt003Support {

    @Test
    void aCodeUsedForSignInCannotBeReplayedButTheNextStepWorks() {
        SeededStaff s = newStaff("replay@a.com", "+919300000011", true);
        String code = totpCodeAtOffset(0);

        assertThat(verify(challenge(s), code).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify(challenge(s), code).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(verify(challenge(s), totpCodeAtOffset(-1)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // older step too
        assertThat(verify(challenge(s), totpCodeAtOffset(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
