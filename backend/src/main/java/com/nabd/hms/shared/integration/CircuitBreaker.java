package com.nabd.hms.shared.integration;

import java.time.Clock;
import java.time.Instant;

/**
 * OPS-012: per-provider consecutive-failure breaker. Closed until {@code threshold} failures in a row, then open
 * for {@code openSeconds} (calls fail fast with {@link CircuitOpenException}). After that calls go through again;
 * one more failure reopens it at once (the failure count is only reset by a success).
 * shortcut: no single-trial half-open state, so several calls may probe at once when it reopens; fine at our
 * volume, swap for Resilience4j (needs an ADR, new dependency) if probes ever pile up.
 */
class CircuitBreaker {

    private final String provider;
    private final int threshold;
    private final long openSeconds;
    private final Clock clock;
    private int failures;
    private Instant openUntil;

    CircuitBreaker(String provider, int threshold, long openSeconds, Clock clock) {
        this.provider = provider;
        this.threshold = threshold;
        this.openSeconds = openSeconds;
        this.clock = clock;
    }

    synchronized void acquire() {
        Instant now = clock.instant();
        if (openUntil != null && now.isBefore(openUntil)) {
            throw new CircuitOpenException(provider, Math.max(1, openUntil.getEpochSecond() - now.getEpochSecond()));
        }
    }

    synchronized void success() {
        failures = 0;
        openUntil = null;
    }

    synchronized void failure() {
        if (++failures >= threshold) {
            openUntil = clock.instant().plusSeconds(openSeconds);
        }
    }

    synchronized boolean isOpen() {
        return openUntil != null && clock.instant().isBefore(openUntil);
    }
}
