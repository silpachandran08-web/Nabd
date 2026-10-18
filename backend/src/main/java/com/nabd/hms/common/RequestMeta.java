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

    public static String userAgent(HttpServletRequest http) {
        String ua = http.getHeader("User-Agent");
        return ua == null ? "unknown-device" : ua;
    }
}
