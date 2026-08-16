package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFC 6238 Appendix B test vector (SHA1, 8-digit "94287082" at T=59 -> our 6-digit truncation is its last 6 digits). */
class TotpServiceTest {

    private final TotpService totp = new TotpService();
    private final byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238TestVector() {
        assertEquals("287082", totp.generate(secret, 59 / 30));
    }

    @Test
    void verifyAcceptsCodeWithinDriftWindow() {
        Instant at = Instant.ofEpochSecond(59);
        assertTrue(totp.verify(secret, "287082", at));
        assertTrue(totp.verify(secret, "287082", at.plusSeconds(30))); // one step of drift tolerated
    }

    @Test
    void verifyRejectsWrongCode() {
        assertFalse(totp.verify(secret, "000000", Instant.ofEpochSecond(59)));
    }
}
