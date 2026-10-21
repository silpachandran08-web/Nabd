package com.nabd.hms.platform.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLT-057: ticket text from clinic staff is cleaned before it is stored, so a support ticket never holds a phone number,
 * an email, a national ID, an MRN or a patient's name. Over-redacting is the safe direction: every match, including a
 * long order number, becomes [redacted].
 *
 * <p>Per market: IN (mobile with or without +91, Aadhaar 12 digits, ABHA 14 digits, PAN) and Saudi Arabia (mobile 05xxxxxxxx or
 * +966, national ID / iqama 10 digits starting 1 or 2). They are all covered by one digit-run rule: 8 or more digits
 * in a row, allowing a single space, dash, dot or bracket between groups. Patient names come from the tenant's own
 * patient list (the caller passes the names found in the text).
 *
 * <p>shortcut: patterns, not NLP. A name that is not on the patient list (a relative, a misspelling) and an ID with fewer
 * than 8 digits are not caught; upgrade to a PHI classifier if the DPO asks for it.
 */
public final class TicketPhiRedactor {

    static final String MARK = "[redacted]";

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    // 8+ digits in groups joined by at most one separator; the lookarounds keep it from cutting a longer word
    private static final Pattern DIGITS = Pattern.compile("(?<![\\p{L}\\d])\\+?\\(?\\d(?:[\\s().-]?\\d){7,}\\)?(?!\\d)");
    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");
    private static final Pattern MRN = Pattern.compile("\\bMRN[\\s:#-]*\\d+\\b", Pattern.CASE_INSENSITIVE);

    public record Result(String text, int count) {
    }

    private TicketPhiRedactor() {
    }

    /** Pattern rules only (no tenant data needed). */
    public static Result redact(String text) {
        return redact(text, List.of());
    }

    /** @param patientNames names of this tenant's patients that occur in the text (see TicketRepository.patientNamesIn) */
    public static Result redact(String text, List<String> patientNames) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        int[] count = {0};
        String out = text;
        // names first, longest first, so "Asha Rao" goes before "Asha"; the marker then cannot be matched again
        List<String> names = new ArrayList<>(patientNames.stream().filter(n -> n != null && n.strip().length() >= 4).toList());
        names.sort((a, b) -> b.length() - a.length());
        for (String n : names) {
            String body = java.util.Arrays.stream(n.strip().split("\\s+")).map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
            out = replaceAll(out, Pattern.compile("(?<![\\p{L}\\d])" + body + "(?![\\p{L}\\d])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), count);
        }
        for (Pattern p : List.of(EMAIL, MRN, PAN, DIGITS)) {
            out = replaceAll(out, p, count);
        }
        return new Result(out, count[0]);
    }

    private static String replaceAll(String in, Pattern p, int[] count) {
        Matcher m = p.matcher(in);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            count[0]++;
            m.appendReplacement(sb, Matcher.quoteReplacement(MARK));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
