package com.nabd.hms.messaging;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** COM-006: a failed or blocked message in words reception can read out. Meta's error text is never shown. */
final class MessageFailureReasons {

    private static final Pattern CODE = Pattern.compile("(?<![0-9])(1[0-9]{5})(?![0-9])");
    private static final String OTHER = "Could not be delivered";
    private static final Map<String, String> BY_CODE = Map.of(
            "131026", "Not on WhatsApp",
            "131047", "Patient has not replied in 24 hours",
            "131050", "Opted out",
            "131049", "Not delivered by WhatsApp to protect the patient experience",
            "132015", "Template paused by Meta",
            "132016", "Template disabled by Meta",
            "132001", "Template not found",
            "132012", "Template details did not match");

    private MessageFailureReasons() {
    }

    /** Null for a message that did not fail. */
    static String of(String status, String error) {
        if (!"failed".equals(status) && !"blocked".equals(status)) {
            return null;
        }
        String e = error == null ? "" : error;
        if (e.equals("recipient opted out")) {
            return "Opted out";
        }
        if (e.equals("no active messaging consent")) {
            return "No messaging consent";
        }
        if (e.startsWith("template is ")) {
            return "Template " + e.substring("template is ".length()).replaceAll("[^a-z]", "") + ", not sendable";
        }
        Matcher m = CODE.matcher(e);
        while (m.find()) {
            String reason = BY_CODE.get(m.group(1));
            if (reason != null) {
                return reason;
            }
        }
        return OTHER;
    }
}
