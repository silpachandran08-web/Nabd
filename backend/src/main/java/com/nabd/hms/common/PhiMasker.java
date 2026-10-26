package com.nabd.hms.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safety net for log text: masks emails, phone numbers (+CC international, and bare IN/AE/SA mobiles),
 * national IDs (Aadhaar, Emirates ID, Qatar QID) and 6-digit codes. Log statements must not carry these at all;
 * this only catches an accident. shortcut: bare 10-digit Saudi IDs and QA 8-digit phones are not matched
 * (they collide with timestamps/ids); extend when a market needs it. */
public final class PhiMasker {

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern INTL_PHONE = Pattern.compile("\\+\\d{8,15}");
    private static final Pattern NATIONAL_ID = Pattern.compile("(?<![\\w.-])(?:784-?\\d{4}-?\\d{7}-?\\d|\\d{4}[ -]\\d{4}[ -]\\d{4}|\\d{12}|[23]\\d{10})(?![\\w.-])");
    private static final Pattern LOCAL_PHONE = Pattern.compile("(?<![\\w.-])0?[5-9]\\d{8,9}(?![\\w.-])");
    private static final Pattern CODE6 = Pattern.compile("(?<![\\w.-])\\d{6}(?![\\w.-])");

    private static final Pattern OTP_NEAR_WORD = Pattern.compile("(?i)\\b(otp|code|pin)\\b([^\\w\\n]{0,4}(?:is|=|:)?\\s*)\\d{4,8}\\b");

    private PhiMasker() {
    }

    public static String mask(String s) {
        if (s == null || s.length() < 6) {
            return s;
        }
        s = EMAIL.matcher(s).replaceAll("$1***@***");
        s = replace(INTL_PHONE, s, 3, 4);
        s = NATIONAL_ID.matcher(s).replaceAll("************");
        s = replace(LOCAL_PHONE, s, 0, 4);
        s = OTP_NEAR_WORD.matcher(s).replaceAll("$1$2******");
        return CODE6.matcher(s).replaceAll("******");
    }

    private static String replace(Pattern p, String s, int keepHead, int keepTail) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    v.substring(0, keepHead) + "*".repeat(v.length() - keepHead - keepTail) + v.substring(v.length() - keepTail)));
        }
        return m.appendTail(sb).toString();
    }
}
