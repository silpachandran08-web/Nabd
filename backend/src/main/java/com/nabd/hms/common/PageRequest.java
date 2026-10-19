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
        if (raw == null) {
            return DEFAULT;
        }
        if (raw < 1 || raw > MAX) {
            String msg = "must be between 1 and " + MAX;
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "limit: " + msg,
                    Map.of("errors", List.of(Map.of("field", "limit", "message", msg))));
        }
        return raw;
    }
}
