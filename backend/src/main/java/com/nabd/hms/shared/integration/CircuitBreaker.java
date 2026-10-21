package com.nabd.hms.shared.integration;

import java.time.Clock;
import java.time.Instant;

/**
 * OPS-012 / COM-003: per-provider breaker. It opens for {@code openSeconds} on EITHER {@code threshold} failures in a
 * row OR a failure rate of at least 50% over the last {@link #WINDOW} calls. Open: calls fail fast with
 * {@link CircuitOpenException}. After the open period it is half-open: exactly one probe call goes through, the rest
 * are refused until it answers; a success closes the breaker (and clears the window), a failure reopens it.
 */
class CircuitBreaker {

    static final int WINDOW = 20;

    private final String provider;
    private final int threshold;
    private final long openSeconds;
    private final Clock clock;
    private int failures;
    private Instant openUntil;
    private boolean probing;
    private final boolean[] window = new boolean[WINDOW]; // true = failure
    private int seen;

    CircuitBreaker(String provider, int threshold, long openSeconds, Clock clock) {
        this.provider = provider;
        this.threshold = threshold;
        this.openSeconds = openSeconds;
        this.clock = clock;
    }

    synchronized void acquire() {
        if (openUntil == null) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(openUntil)) {
            throw new CircuitOpenException(provider, Math.max(1, openUntil.getEpochSecond() - now.getEpochSecond()));
        }
        if (probing) {
            throw new CircuitOpenException(provider, 1); // half-open: the single probe is still out
        }
        probing = true;
    }

    synchronized void success() {
        if (openUntil != null) {
            if (clock.instant().isBefore(openUntil)) {
                return; // a call admitted before the breaker opened: only an answer after the open period may close it
            }
            openUntil = null; // the half-open probe answered: close and start a fresh window
            probing = false;
            seen = 0;
            java.util.Arrays.fill(window, false);
        }
        failures = 0;
        window[seen++ % WINDOW] = false;
    }

    synchronized void failure() {
        boolean wasProbe = probing;
        probing = false;
        window[seen++ % WINDOW] = true;
        int failed = 0;
        for (boolean f : window) {
            failed += f ? 1 : 0;
        }
        if (wasProbe || ++failures >= threshold || (seen >= WINDOW && failed * 2 >= WINDOW)) {
            openUntil = clock.instant().plusSeconds(openSeconds);
        }
    }

    synchronized boolean isOpen() {
        return openUntil != null && clock.instant().isBefore(openUntil);
    }
}
