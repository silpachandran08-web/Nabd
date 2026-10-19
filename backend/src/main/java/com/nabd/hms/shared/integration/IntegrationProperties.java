package com.nabd.hms.shared.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * OPS-012: timeouts and circuit breaker for outbound HTTP (app.integration.*). Defaults connect 2 s / read 5 s;
 * {@code providers.<name>} overrides them for one provider (documented in application.yml). The breaker opens
 * after {@code circuitFailureThreshold} consecutive failures (IO error, timeout, 429, 5xx) and fails calls fast
 * for {@code circuitOpenSeconds}.
 */
@ConfigurationProperties(prefix = "app.integration")
public record IntegrationProperties(
        int connectTimeoutMs,
        int readTimeoutMs,
        int circuitFailureThreshold,
        int circuitOpenSeconds,
        Map<String, Provider> providers
) {
    public record Provider(Integer connectTimeoutMs, Integer readTimeoutMs) {
    }

    int connectTimeoutMs(String provider) {
        Provider p = providers == null ? null : providers.get(provider);
        return p != null && p.connectTimeoutMs() != null ? p.connectTimeoutMs() : connectTimeoutMs;
    }

    int readTimeoutMs(String provider) {
        Provider p = providers == null ? null : providers.get(provider);
        return p != null && p.readTimeoutMs() != null ? p.readTimeoutMs() : readTimeoutMs;
    }
}
