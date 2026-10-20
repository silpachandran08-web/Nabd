package com.nabd.hms.shared.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OPS-016: app.relay.* (docs/architecture/events.md section 7). enabled is true in the worker profile
 * (application-worker.yml). streamMaxlen is the approximate MAXLEN of every ev:&lt;region&gt;:&lt;domain&gt; stream: 1,000,000
 * entries = 7 days at ~1.65 events/s (~143k events/day) on one domain stream; recompute as 7 x the busiest day.
 * directUrl/directUsername/directPassword: the direct (non-PgBouncer) connection for the advisory lock and LISTEN;
 * blank falls back to spring.datasource.*.
 */
@ConfigurationProperties(prefix = "app.relay")
public record RelayProperties(
        boolean enabled,
        @DefaultValue("500") int batchSize,
        @DefaultValue("10") int leaseSeconds,
        @DefaultValue("2000") int pollMs,
        @DefaultValue("2000") int lockRetryMs,
        @DefaultValue("1000") int errorBackoffMs,
        @DefaultValue("1000000") long streamMaxlen,
        String directUrl,
        String directUsername,
        String directPassword
) {
    public RelayProperties {
        if (batchSize < 1 || leaseSeconds < 1 || pollMs < 1 || errorBackoffMs < 1 || streamMaxlen < 1) {
            throw new IllegalArgumentException("app.relay batch-size, lease-seconds, poll-ms, error-backoff-ms and stream-maxlen must be positive");
        }
        // a standby retries the lock this often; failover must stay within 10 s (OPS-016)
        if (lockRetryMs < 1 || lockRetryMs > 5000) {
            throw new IllegalArgumentException("app.relay.lock-retry-ms must be between 1 and 5000");
        }
    }
}
