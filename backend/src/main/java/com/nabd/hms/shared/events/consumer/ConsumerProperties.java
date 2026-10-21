package com.nabd.hms.shared.events.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** OPS-017: app.events.consumer.* (docs/architecture/events.md section 7). */
@ConfigurationProperties(prefix = "app.events.consumer")
public record ConsumerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String name,                 // consumer name inside each group; blank = host name
        @DefaultValue({"in", "ae", "sa", "qa"}) List<String> regions,
        @DefaultValue("50") int batchSize,
        @DefaultValue("60000") long claimIdleMs,       // XAUTOCLAIM entries another consumer left pending this long
        @DefaultValue("8") int maxAttempts,            // failures before ev:<region>:dlq
        @DefaultValue("1000") long retryBaseMs,        // backoff base * 2^(failures-1), capped
        @DefaultValue("30000") long retryMaxMs,
        @DefaultValue("86400") long appliedTtlSeconds) {
}
