package com.nabd.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        int accessTokenTtlMinutes,
        int refreshTokenTtlDays,
        int mfaChallengeTtlMinutes,
        int lockoutThreshold,
        int rateLimitPerIpPerMinute
) {
}
