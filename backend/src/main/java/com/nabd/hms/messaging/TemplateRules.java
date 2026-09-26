package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Meta's template rules, checked before submission so the clinic gets the reason now rather than a
 * rejection hours later — and NB-191's param rules, checked on every send. */
final class TemplateRules {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,100}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(_[A-Z]{2})?");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\d+)}}");
    private static final Pattern ADJACENT_VARIABLES = Pattern.compile("}}\\s*\\{\\{");
    private static final int MAX_BODY = 1024;
    static final int MAX_HEADER = 60;

    private TemplateRules() {
    }

    static void checkName(String name, String language) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw invalid("Template name must be 1-100 lowercase letters, digits or underscores.");
        }
        if (language == null || !LANGUAGE.matcher(language).matches()) {
            throw invalid("Language must be a WhatsApp language code such as en, ar or en_US.");
        }
    }

    /** @return how many {{n}} slots the body has */
    static int checkBody(String body, List<String> examples) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > MAX_BODY) {
            throw invalid("Message text must be between 1 and " + MAX_BODY + " characters.");
        }
        int count = 0;
        Matcher m = VARIABLE.matcher(text);
        while (m.find()) {
            count++;
            if (Integer.parseInt(m.group(1)) != count) {
                throw invalid("Variables must be numbered {{1}}, {{2}}, {{3}}… in order, with none skipped or repeated.");
            }
        }
        if (count > 0 && (VARIABLE.matcher(text).lookingAt() || text.matches("(?s).*\\{\\{\\d+}}$"))) {
            throw invalid("Message text can't start or end with a variable — add words before and after it.");
        }
        if (ADJACENT_VARIABLES.matcher(text).find()) {
            throw invalid("Two variables can't sit next to each other — put words between them.");
        }
        if (examples == null || examples.size() != count || examples.stream().anyMatch(e -> e == null || e.isBlank())) {
            throw invalid("Give one example value for each of the " + count + " variables — Meta's reviewer needs them.");
        }
        return count;
    }

    /** NB-191: callers only ever fill slots; Meta itself refuses newlines, tabs and runs of 4+ spaces in a param. */
    static void checkParams(List<String> params, int expected) {
        if (params.size() != expected) {
            throw invalid("This template takes " + expected + " values, got " + params.size() + ".");
        }
        for (String p : params) {
            if (p == null || p.isBlank() || p.contains("\n") || p.contains("\t") || p.contains("    ")) {
                throw invalid("Template values can't be blank or contain line breaks, tabs or long runs of spaces.");
            }
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-whatsapp-template", "Invalid WhatsApp template", detail);
    }
}
