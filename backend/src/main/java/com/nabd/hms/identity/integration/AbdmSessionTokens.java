package com.nabd.hms.identity.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * IDC-016: the bridge's gateway session token. Fetched on first use, reused until {@code refreshMargin} before it
 * expires, then fetched again, so a caller never sends an expired token. {@link #invalidate} drops it after a 401.
 * Single-flight: concurrent callers wait for one fetch.
 */
final class AbdmSessionTokens {

    record Token(String value, Duration lifetime) {
        @Override
        public String toString() {
            return "Token[lifetime=" + lifetime + "]"; // never the value
        }
    }

    private final Supplier<Token> fetch;
    private final Clock clock;
    private final Duration margin;
    private String token;
    private Instant refreshAt = Instant.MIN;

    AbdmSessionTokens(Supplier<Token> fetch, Clock clock, Duration margin) {
        this.fetch = fetch;
        this.clock = clock;
        this.margin = margin;
    }

    synchronized String current() {
        Instant now = clock.instant();
        if (token == null || !now.isBefore(refreshAt)) {
            Token t = fetch.get();
            token = t.value();
            // a lifetime shorter than the margin would refresh on every call; that is correct, not an error
            refreshAt = now.plus(t.lifetime()).minus(margin);
        }
        return token;
    }

    synchronized void invalidate() {
        token = null;
    }
}
