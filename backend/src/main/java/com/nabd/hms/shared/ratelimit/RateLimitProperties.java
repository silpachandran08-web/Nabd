package com.nabd.hms.shared.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** PLT-008: app.rate-limit.* token buckets (per minute, burst = bucket size). See application.yml for the defaults. */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(int tenantPerMinute, int tenantBurst, int userPerMinute, int userBurst,
                                  int ipPerMinute, int ipBurst, int planRefreshSeconds) {
}
