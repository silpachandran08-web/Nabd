package com.nabd.hms.clinical.templates;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Size and shape limits for note templates. Pure functions: no clinical content is checked or supplied. */
final class NoteTemplateRules {

    static final int MAX_NAME = 80;
    static final int MAX_SECTION = 4000;
    static final int MAX_TAGS = 10;
    static final int MAX_TAG = 30;
    static final int MAX_TEMPLATES_PER_DOCTOR = 100;

    private NoteTemplateRules() {
    }

    static String name(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) {
            throw invalid("A template needs a name.");
        }
        if (v.length() > MAX_NAME) {
            throw invalid("The template name can be at most " + MAX_NAME + " characters.");
        }
        return v;
    }

    /** Sections keep the doctor's text as typed (only null becomes empty); over-long text is refused, never cut. */
    static String section(String field, String raw) {
        String v = raw == null ? "" : raw;
        if (v.length() > MAX_SECTION) {
            throw invalid("The " + field + " section can be at most " + MAX_SECTION + " characters.");
        }
        return v;
    }

    /** Trims, drops blanks, removes case-insensitive duplicates (first spelling wins), keeps order. */
    static List<String> tags(List<String> raw) {
        Map<String, String> seen = new LinkedHashMap<>();
        if (raw != null) {
            for (String t : raw) {
                String v = t == null ? "" : t.strip();
                if (v.isEmpty()) {
                    continue;
                }
                if (v.length() > MAX_TAG) {
                    throw invalid("A tag can be at most " + MAX_TAG + " characters.");
                }
                seen.putIfAbsent(v.toLowerCase(java.util.Locale.ROOT), v);
            }
        }
        if (seen.size() > MAX_TAGS) {
            throw invalid("A template can have at most " + MAX_TAGS + " tags.");
        }
        return new ArrayList<>(seen.values());
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-note-template", "Invalid note template", detail);
    }
}
