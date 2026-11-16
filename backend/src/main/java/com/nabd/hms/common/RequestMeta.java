package com.nabd.hms.common;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP / device label extraction — used by every controller that writes an audit or session row. */
public final class RequestMeta {

    private static final String UNKNOWN_IP = "0.0.0.0";

    private RequestMeta() {
    }

    public static String clientIp(HttpServletRequest http) {
        // Never read X-Forwarded-For here: Tomcat's RemoteIpValve (application.yml) has already rewritten
        // getRemoteAddr() when, and only when, the peer is a configured trusted proxy.
        String addr = http.getRemoteAddr();
        if (addr == null) {
            return UNKNOWN_IP;
        }
        int scope = addr.indexOf('%'); // fe80::1%eth0 is not valid for a Postgres inet cast
        String literal = scope < 0 ? addr : addr.substring(0, scope);
        if (!literal.matches("[0-9a-fA-F:.]+")) { // also keeps InetAddress from attempting a DNS lookup
            return UNKNOWN_IP;
        }
        try {
            return java.net.InetAddress.getByName(literal).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return UNKNOWN_IP;
        }
    }

    public static final String UNKNOWN_DEVICE = "Unknown device";

    /** PLT-013: the client's X-Device-Label ("Front desk PC 2"), control/format characters removed, whitespace collapsed,
     * at most 64 characters; UNKNOWN_DEVICE when absent or empty. Display only: never use it for a security decision. */
    public static String deviceLabel(HttpServletRequest http) {
        return sanitiseDeviceLabel(http.getHeader("X-Device-Label"));
    }

    static String sanitiseDeviceLabel(String raw) {
        if (raw == null) {
            return UNKNOWN_DEVICE;
        }
        String s = raw.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}<>]", " ").replaceAll("\\s+", " ").strip();
        if (s.codePointCount(0, s.length()) > 64) {
            s = s.substring(0, s.offsetByCodePoints(0, 64)).strip();
        }
        return s.isEmpty() ? UNKNOWN_DEVICE : s;
    }

    public static String userAgent(HttpServletRequest http) {
        String ua = http.getHeader("User-Agent");
        return ua == null ? "unknown-device" : ua;
    }
}
