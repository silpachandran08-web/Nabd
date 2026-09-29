package com.nabd.hms.common;

/**
 * One way to read a mobile number, for staff login and patient records alike. Typed text varies
 * ("98765 43210", "+91 98765-43210", "0091…"); comparing it raw let the same number look like two
 * (no OTP, missed duplicate patients). India (91) and Saudi Arabia (966) local formats get their
 * country code from the clinic's region; anything already carrying one is kept as is.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** E.164 digits, no "+". */
    public static String digits(String raw, String region) {
        String trimmed = raw == null ? "" : raw.strip();
        String d = trimmed.replaceAll("\\D", "");
        if (trimmed.startsWith("+")) {
            return d;
        }
        if (d.startsWith("00")) {
            return d.substring(2);
        }
        if ("KSA".equals(region)) {
            if (d.length() == 10 && d.startsWith("05")) return "966" + d.substring(1);
            if (d.length() == 9 && d.startsWith("5")) return "966" + d;
            return d;
        }
        if (d.length() == 11 && d.startsWith("0")) return "91" + d.substring(1);
        if (d.length() == 10) return "91" + d;
        return d;
    }

    /** "+" + digits — the stored form. Blank input stays as typed so validation can reject it. */
    public static String e164(String raw, String region) {
        String d = digits(raw, region);
        return d.isEmpty() ? raw : "+" + d;
    }
}
