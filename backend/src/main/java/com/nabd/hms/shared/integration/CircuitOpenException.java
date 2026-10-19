package com.nabd.hms.shared.integration;

/** OPS-012: the provider's circuit breaker is open; the call was refused without touching the network. Callers on
 * the outbox path just throw it on, and the event is retried later with its normal backoff (next_attempt_at). */
public class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String provider, long retryInSeconds) {
        super("circuit open for " + provider + ", retry in " + retryInSeconds + " s");
    }
}
