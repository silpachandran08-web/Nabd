package com.nabd.comms.sms;

import java.util.regex.Pattern;

/**
 * COM-026.2: a send must equal the registered DLT body character for character, except that each {#var#} stands for 1 to 30
 * characters (the DLT variable limit) on one line. No trimming, no case folding, no Unicode normalisation: the operator's scrubber
 * compares the same way, and a mismatch there is a silent drop.
 */
final class DltTemplateMatcher {

    static final String VAR = "{#var#}";
    static final int MAX_VAR = 30;

    private DltTemplateMatcher() {}

    static boolean matches(String registered, String text) {
        StringBuilder re = new StringBuilder();
        String[] literal = registered.split(Pattern.quote(VAR), -1);
        for (int i = 0; i < literal.length; i++) {
            if (i > 0) {
                re.append(".{1,").append(MAX_VAR).append('}'); // '.' is one code point and never a line break
            }
            re.append(Pattern.quote(literal[i]));
        }
        return Pattern.compile(re.toString()).matcher(text).matches();
    }
}
