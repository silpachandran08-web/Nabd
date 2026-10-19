package com.nabd.hms.clinical.dto;

import java.util.List;
import java.util.Map;

/**
 * CLN-004: an uploaded dictionary version. Same field names as the story's bundle
 * { classes[], terms[], stems[], exclusions[], pregnancyRisk[], controlled[] }, plus labels
 * ({"controlled": {"en": "...", "ar": "..."}}) for the region's controlled-substance warning.
 * terms/stems carry classId; pregnancyRisk/controlled carry matchKind ("term" = whole word, "stem" = substring).
 */
public record SafetyDictionaryBundle(List<ClassDef> classes, List<Item> terms, List<Item> stems, List<Item> exclusions,
                                     List<Item> pregnancyRisk, List<Item> controlled, Map<String, Map<String, String>> labels) {

    public record ClassDef(String id, List<String> crossReactsTo) {
    }

    public record Item(String classId, String value, String lang, String matchKind) {
    }
}
