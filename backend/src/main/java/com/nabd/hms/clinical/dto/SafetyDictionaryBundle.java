package com.nabd.hms.clinical.dto;

import java.util.List;
import java.util.Map;

/**
 * CLN-004: an uploaded dictionary version. Same field names as the story's bundle
 * { classes[], terms[], stems[], exclusions[], pregnancyRisk[], controlled[] }, plus labels
 * ({"controlled": {"en": "...", "ar": "..."}}) for the region's controlled-substance warning.
 * terms/stems carry classId; pregnancyRisk/controlled carry matchKind ("term" = whole word, "stem" = substring).
 * CLN-024: validityRules (optional) = the Rx validity / refill rules of this version; omitted = the version keeps the rules of
 * the version before it. classId: a drug class id, "schedule:<flag>", "controlled" or "*" (any other line).
 */
public record SafetyDictionaryBundle(List<ClassDef> classes, List<Item> terms, List<Item> stems, List<Item> exclusions,
                                     List<Item> pregnancyRisk, List<Item> controlled, Map<String, Map<String, String>> labels,
                                     List<ValidityRule> validityRules) {

    /** Keeps the pre-CLN-024 shape (no validity rules) compiling. */
    public SafetyDictionaryBundle(List<ClassDef> classes, List<Item> terms, List<Item> stems, List<Item> exclusions,
                                  List<Item> pregnancyRisk, List<Item> controlled, Map<String, Map<String, String>> labels) {
        this(classes, terms, stems, exclusions, pregnancyRisk, controlled, labels, null);
    }

    /** fixed: the values apply whatever the prescriber enters (a controlled class: maxRefills 0 and maxDays = defaultDays). */
    public record ValidityRule(String classId, Integer defaultDays, Integer maxDays, Integer maxRefills, Boolean fixed) {
    }

    public record ClassDef(String id, List<String> crossReactsTo) {
    }

    public record Item(String classId, String value, String lang, String matchKind) {
    }
}
