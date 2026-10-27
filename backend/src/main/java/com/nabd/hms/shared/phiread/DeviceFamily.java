package com.nabd.hms.shared.phiread;

import java.util.Locale;

/** PLT-041: the coarse device class of a User-Agent ("iPhone", "Android phone", "Windows", ...), so the log answers "which kind of device" without keeping the identifying string. */
final class DeviceFamily {

    static final java.util.List<String> ALL = java.util.List.of("iPhone", "iPad", "Android phone", "Android tablet", "Windows", "Mac", "Linux", "ChromeOS", "Bot", "Other", "Unknown");

    private DeviceFamily() {
    }

    static String of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider") || ua.contains("curl/") || ua.contains("python-requests")) {
            return "Bot";
        }
        if (ua.contains("iphone")) {
            return "iPhone";
        }
        if (ua.contains("ipad")) {
            return "iPad";
        }
        if (ua.contains("android")) {
            return ua.contains("mobile") ? "Android phone" : "Android tablet";
        }
        if (ua.contains("cros")) {
            return "ChromeOS";
        }
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("macintosh") || ua.contains("mac os x")) {
            return "Mac";
        }
        if (ua.contains("linux") || ua.contains("x11")) {
            return "Linux";
        }
        return "Other";
    }

    /** The address when it is an IPv4/IPv6 literal (no DNS lookup is ever made), else null. */
    static String validIp(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        int scope = s.indexOf('%');
        String literal = scope < 0 ? s : s.substring(0, scope);
        if (literal.isEmpty() || literal.length() > 45 || !literal.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        // a dotted name or a bare number would make getByName do a DNS lookup: only a strict IPv4 or anything with ':' (an IPv6 literal) goes through
        if (!literal.contains(":") && !literal.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")) {
            return null;
        }
        try {
            return java.net.InetAddress.getByName(literal).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }
}
