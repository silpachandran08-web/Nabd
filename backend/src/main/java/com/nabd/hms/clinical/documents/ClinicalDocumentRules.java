package com.nabd.hms.clinical.documents;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DOC-030: which labelled fields each document type has, and what must be filled before it can be issued.
 * Deliberately only field NAMES and "is it filled" checks - no pre-written clinical or legal wording lives in
 * the backend. Everything printed is text the doctor typed.
 */
final class ClinicalDocumentRules {

    static final String VISIT_SUMMARY = "visit_summary";
    static final String REFERRAL = "referral";
    static final String CERTIFICATE = "certificate";

    static final int MAX_FIELD_LENGTH = 4000;

    private static final Map<String, Set<String>> FIELDS = Map.of(
            VISIT_SUMMARY, Set.of("diagnosis", "assessment", "plan", "medicines", "patientInstructions"),
            REFERRAL, Set.of("referredTo", "department", "reason", "findings", "notes"),
            CERTIFICATE, Set.of("purpose", "findings", "restFrom", "restTo", "remarks"));

    /** Fields that must be non-blank to issue. A visit summary needs at least one filled field instead. */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            REFERRAL, List.of("referredTo", "reason"),
            CERTIFICATE, List.of("purpose"));

    private static final Set<String> DATE_FIELDS = Set.of("restFrom", "restTo");

    private ClinicalDocumentRules() {
    }

    static boolean isKnownType(String type) {
        return type != null && FIELDS.containsKey(type);
    }

    static Set<String> allowedFields(String type) {
        return FIELDS.get(type);
    }

    /**
     * Normalises a body for storing as a DRAFT: rejects unknown keys / over-long values / malformed dates,
     * trims values and drops blank ones. Drafts may be incomplete.
     */
    static Map<String, String> sanitise(String type, Map<String, String> raw) {
        if (!isKnownType(type)) {
            throw bad("unknown-document-type", "Unknown document type", "Document type must be visit_summary, referral or certificate.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        Set<String> allowed = FIELDS.get(type);
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                throw bad("unknown-document-field", "Unknown field", "Field '" + e.getKey() + "' does not belong to a " + type + " document.");
            }
            String v = e.getValue() == null ? "" : e.getValue().strip();
            if (v.length() > MAX_FIELD_LENGTH) {
                throw bad("document-field-too-long", "Field too long", "Field '" + e.getKey() + "' is longer than " + MAX_FIELD_LENGTH + " characters.");
            }
            if (v.isEmpty()) {
                continue;
            }
            if (DATE_FIELDS.contains(e.getKey()) && parseDate(v) == null) {
                throw bad("invalid-document-date", "Invalid date", "Field '" + e.getKey() + "' must be a date as yyyy-mm-dd.");
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** Extra checks that only apply when issuing (the body is already sanitised). */
    static void requireIssuable(String type, Map<String, String> body) {
        if (VISIT_SUMMARY.equals(type)) {
            if (body.isEmpty()) {
                throw bad("document-incomplete", "Document incomplete", "Fill at least one field before issuing the visit summary.");
            }
            return;
        }
        for (String f : REQUIRED.getOrDefault(type, List.of())) {
            if (body.get(f) == null || body.get(f).isBlank()) {
                throw bad("document-incomplete", "Document incomplete", "Field '" + f + "' is required before issuing.");
            }
        }
        if (CERTIFICATE.equals(type) && body.containsKey("restFrom") && body.containsKey("restTo")
                && parseDate(body.get("restTo")).isBefore(parseDate(body.get("restFrom")))) {
            throw bad("invalid-document-date", "Invalid period", "The end of the period cannot be before its start.");
        }
    }

    static LocalDate parseDate(String v) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ApiException bad(String slug, String title, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, slug, title, detail);
    }
}
