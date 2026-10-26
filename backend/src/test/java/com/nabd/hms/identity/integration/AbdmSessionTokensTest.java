package com.nabd.hms.identity.integration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-016 unit: the session token is refreshed before it expires, for a simulated 24 h, and never served stale. */
class AbdmSessionTokensTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void refreshesBeforeExpiryAndNeverServesAnExpiredTokenOver24Hours() {
        MutableClock clock = new MutableClock();
        AtomicInteger fetches = new AtomicInteger();
        Instant[] expiry = new Instant[1];
        AbdmSessionTokens tokens = new AbdmSessionTokens(() -> {
            int n = fetches.incrementAndGet();
            expiry[0] = clock.now.plus(Duration.ofMinutes(10));
            return new AbdmSessionTokens.Token("t" + n, Duration.ofMinutes(10));
        }, clock, Duration.ofSeconds(60));

        Instant end = clock.now.plus(Duration.ofHours(24));
        while (clock.now.isBefore(end)) {
            String t = tokens.current();
            assertThat(clock.now).as("token " + t + " is still valid when handed out").isBefore(expiry[0]);
            clock.now = clock.now.plusSeconds(30);
        }
        // one fetch per (10 min - 60 s) window: 24 h / 9 min = 160
        assertThat(fetches.get()).isBetween(155, 165);
    }

    @Test
    void reusesTheTokenInsideTheWindowAndInvalidateForcesAFetch() {
        MutableClock clock = new MutableClock();
        AtomicInteger fetches = new AtomicInteger();
        AbdmSessionTokens tokens = new AbdmSessionTokens(() -> new AbdmSessionTokens.Token("t" + fetches.incrementAndGet(), Duration.ofMinutes(10)), clock, Duration.ofSeconds(60));
        assertThat(tokens.current()).isEqualTo("t1");
        clock.now = clock.now.plusSeconds(500);
        assertThat(tokens.current()).isEqualTo("t1");
        clock.now = clock.now.plusSeconds(41); // 541 s = inside the 60 s margin of the 600 s lifetime
        assertThat(tokens.current()).isEqualTo("t2");
        tokens.invalidate();
        assertThat(tokens.current()).isEqualTo("t3");
        assertThat(new AbdmSessionTokens.Token("secret-value", Duration.ofSeconds(1)).toString()).doesNotContain("secret-value");
    }
}
