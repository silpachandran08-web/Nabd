package com.nabd.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OPS-014: perEventTimeoutMs is the longest one event may take (the slowest handler's connect + read timeout).
 * A claim holds its lease for leaseSeconds; if the batch can outlive it, another instance reclaims and re-sends.
 * So startup fails unless batchSize x perEventTimeoutMs < leaseSeconds, or extendLeasePerEvent renews the lease
 * before each event (then only perEventTimeoutMs < leaseSeconds is needed).
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        long pollIntervalMs,
        int batchSize,
        int leaseSeconds,
        long perEventTimeoutMs,
        boolean extendLeasePerEvent
) {
    public OutboxProperties {
        if (batchSize < 1 || leaseSeconds < 1 || perEventTimeoutMs < 1) {
            throw new IllegalArgumentException("app.outbox batch-size, lease-seconds and per-event-timeout-ms must be positive");
        }
        long worstCaseMs = extendLeasePerEvent ? perEventTimeoutMs : batchSize * perEventTimeoutMs;
        if (worstCaseMs >= leaseSeconds * 1000L) {
            throw new IllegalArgumentException("app.outbox: " + (extendLeasePerEvent ? "per-event-timeout-ms" : "batch-size x per-event-timeout-ms")
                    + " (" + worstCaseMs + " ms) must be below lease-seconds (" + leaseSeconds
                    + " s), or a reclaimed event is sent twice; lower batch-size or set extend-lease-per-event: true");
        }
    }
}
