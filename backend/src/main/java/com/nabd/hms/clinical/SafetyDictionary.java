package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLN-004: one region's dictionary version as an immutable in-memory snapshot. The CONTENT (classes, brands,
 * stems, exclusions, pregnancy-risk and controlled lists, warning labels) lives in safety_dictionary_entries and is
 * owned by the Clinical Safety Officer; the matching ALGORITHM stays in {@link AllergyDrugClasses} and
 * {@link RxSafetyChecks}. Built only from {@link Entry} rows, so the database loader and the bundle upload share
 * one code path.
 *
 * @param controlledLabel warning label per language ("en" always present, "ar"/"hi" when the CSO supplies one)
 */
record SafetyDictionary(String region, int version, Instant approvedAt, String approvedBy,
                        List<AllergyDrugClasses.DrugClass> classes, Set<String> notTheStem,
                        Terms pregnancyRisk, Terms controlled, Map<String, String> controlledLabel) {

    static final String KIND_CLASS = "allergy_class";
    static final String KIND_PREGNANCY = "pregnancy_risk";
    static final String KIND_CONTROLLED = "controlled";
    static final String KIND_NOT_THE_STEM = "not_the_stem";

    /** Whole-word terms and anywhere-in-a-word stems (same two kinds as {@link AllergyDrugClasses}). */
    record Terms(List<String> terms, List<String> stems) {
    }

    /** One row of safety_dictionary_entries. */
    record Entry(String kind, String classId, String matchKind, String value, String lang, List<String> crossReactsTo) {
    }

    /** What is stamped on a signed prescription and shown under warnings, e.g. "IN v1". */
    String label() {
        return region + " v" + version;
    }

    /** Tenants store IN or KSA (V1); dictionaries are keyed by the regional cell code (IN, SA, AE, QA). */
    static String regionOfTenant(String tenantRegion) {
        return "KSA".equals(tenantRegion) ? "SA" : "IN";
    }

    /** Entries in load order (class order = first appearance). cross_reacts_to is the union over a class's rows. */
    static SafetyDictionary fromEntries(String region, int version, Instant approvedAt, String approvedBy,
                                        List<Entry> entries, Map<String, String> controlledLabel) {
        Map<String, List<String>[]> classes = new LinkedHashMap<>(); // id -> {terms, stems, crossReactsTo}
        Set<String> notTheStem = new LinkedHashSet<>();
        List<String> pregTerms = new ArrayList<>();
        List<String> pregStems = new ArrayList<>();
        List<String> ctrlTerms = new ArrayList<>();
        List<String> ctrlStems = new ArrayList<>();
        for (Entry e : entries) {
            boolean stem = "stem".equals(e.matchKind());
            switch (e.kind()) {
                case KIND_CLASS -> {
                    @SuppressWarnings("unchecked")
                    List<String>[] c = classes.computeIfAbsent(e.classId(),
                            k -> new List[] {new ArrayList<>(), new ArrayList<>(), new ArrayList<>()});
                    (stem ? c[1] : c[0]).add(e.value());
                    for (String x : e.crossReactsTo() == null ? List.<String>of() : e.crossReactsTo()) {
                        if (!c[2].contains(x)) {
                            c[2].add(x);
                        }
                    }
                }
                case KIND_NOT_THE_STEM -> notTheStem.add(e.value());
                case KIND_PREGNANCY -> (stem ? pregStems : pregTerms).add(e.value());
                case KIND_CONTROLLED -> (stem ? ctrlStems : ctrlTerms).add(e.value());
                default -> throw new IllegalArgumentException("unknown dictionary entry kind " + e.kind());
            }
        }
        List<AllergyDrugClasses.DrugClass> out = new ArrayList<>();
        classes.forEach((id, c) -> out.add(new AllergyDrugClasses.DrugClass(id, List.copyOf(c[0]), List.copyOf(c[1]), List.copyOf(c[2]))));
        return new SafetyDictionary(region, version, approvedAt, approvedBy, List.copyOf(out), Set.copyOf(notTheStem),
                new Terms(List.copyOf(pregTerms), List.copyOf(pregStems)), new Terms(List.copyOf(ctrlTerms), List.copyOf(ctrlStems)),
                Map.copyOf(controlledLabel));
    }
}
