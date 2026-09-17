package com.nabd.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        long pollIntervalMs,
        int batchSize,
        int leaseSeconds
) {
}
