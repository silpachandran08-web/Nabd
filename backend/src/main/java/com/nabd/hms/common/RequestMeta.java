package com.nabd.hms.common;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP / device label extraction — used by every controller that writes an audit or session row. */
public final class RequestMeta {

    private RequestMeta() {
    }

    public static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest http) {
        String ua = http.getHeader("User-Agent");
        return ua == null ? "unknown-device" : ua;
    }
}
