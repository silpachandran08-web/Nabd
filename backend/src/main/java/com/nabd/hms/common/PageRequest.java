package com.nabd.hms.common;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** PLT-027: one rule for every list endpoint's `limit`: default 25, 1..100, anything else is a 400 naming the field. */
public final class PageRequest {

    public static final int DEFAULT = 25;
    public static final int MAX = 100;

    private PageRequest() {
    }

    public static int limit(Integer raw) {
        return raw == null ? DEFAULT : limit(raw, MAX);
    }

    /** Same rule with an endpoint-specific ceiling (OPS-008: the registry pages up to 200). */
    public static int limit(int raw, int max) {
        if (raw < 1 || raw > max) {
            String msg = "must be between 1 and " + max;
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "limit: " + msg,
                    Map.of("errors", List.of(Map.of("field", "limit", "message", msg)), "code", "INVALID_LIMIT"));
        }
        return raw;
    }
}
