package com.nabd.hms.common.valkey;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** PLT-008: app.valkey.* (docs/architecture/valkey.md). A blank url means no Valkey: nothing connects and callers degrade. */
@ConfigurationProperties(prefix = "app.valkey")
public record ValkeyProperties(String url, Integer connectTimeoutMs, Integer commandTimeoutMs) {

    public boolean configured() {
        return url != null && !url.isBlank();
    }
}
