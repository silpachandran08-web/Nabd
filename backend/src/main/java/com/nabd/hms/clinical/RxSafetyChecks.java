package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.RxReasonOption;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * NB-111/NB-113: same shape as PrescriptionService's existing allergy check — a small, named,
 * case-insensitive substring match against the free-text drug name, not a real drug database
 * (this app has no source for one, same limitation the allergy check already documents). Short,
 * illustrative lists of well-known examples, not a certified regulatory or clinical reference.
 * CLN-004: the lists themselves moved unchanged into the region's {@link SafetyDictionary}; the rules stay here.
 */
final class RxSafetyChecks {

    private RxSafetyChecks() {
    }

    static final String KIND_ALLERGY = "allergy";
    static final String KIND_PREGNANCY = "pregnancy";
    static final String KIND_CONTROLLED = "controlled";

    /** Named reasons per warning kind (wireframe rxoverride). The code is stored; the label is for display. */
    static List<RxReasonOption> reasonOptions(String kind) {
        return switch (kind) {
            case KIND_ALLERGY -> List.of(
                    new RxReasonOption("prior_mild_reaction_only", "Documented prior mild reaction only"),
                    new RxReasonOption("no_suitable_alternative", "No suitable therapeutic alternative"),
                    new RxReasonOption("direct_supervision", "Given under direct supervision"));
            case KIND_PREGNANCY -> List.of(
                    new RxReasonOption("benefit_outweighs_risk", "Clinical benefit outweighs risk"),
                    new RxReasonOption("no_lower_risk_alternative", "No lower-risk alternative available"),
                    new RxReasonOption("obstetric_consultation", "Documented obstetric consultation"));
            case KIND_CONTROLLED -> List.of(
                    new RxReasonOption("documentation_confirmed", "Additional documentation recorded"));
            default -> List.of();
        };
    }

    static String reasonLabel(String kind, String code) {
        return reasonOptions(kind).stream().filter(o -> o.code().equals(code)).map(RxReasonOption::label).findFirst().orElse(null);
    }

    /** True when the drug name contains the recorded substance, or the substance and the drug share a drug class
     * (see {@link AllergyDrugClasses}: penicillins, cephalosporins, sulfonamide antibiotics, NSAIDs and others,
     * with spelling variants and brand names). Name-based only; it leans towards a needless warning over a miss. */
    static boolean allergyMatches(SafetyDictionary dict, String drugName, String substance) {
        return AllergyDrugClasses.matches(dict, drugName, substance);
    }

    /** NB-113: fires for any patient of childbearing potential — female, roughly 12-55 — regardless of specialty. */
    static String pregnancyWarning(SafetyDictionary dict, String drugName, String gender, LocalDate dob, LocalDate today) {
        if (!"female".equals(gender)) {
            return null;
        }
        int age = Period.between(dob, today).getYears();
        if (age < 12 || age > 55) {
            return null;
        }
        return matches(drugName, dict.pregnancyRisk())
                ? "Caution: avoid in pregnancy/lactation for patients of childbearing potential"
                : null;
    }

    /** DOC-021: escalation when a clinician has RECORDED the patient as pregnant or lactating. It applies to any
     * patient the status is recorded for (no sex/age gate) and only ever adds to {@link #pregnancyWarning}; any
     * other status (not_pregnant, unknown, not documented) returns null so the caller behaves exactly as before. */
    static String recordedStatusWarning(SafetyDictionary dict, String drugName, String pregnancyStatus) {
        if (!matches(drugName, dict.pregnancyRisk())) {
            return null;
        }
        if ("pregnant".equals(pregnancyStatus)) {
            return "Warning: patient is recorded as PREGNANT; this drug is on the pregnancy-risk list";
        }
        if ("lactating".equals(pregnancyStatus)) {
            return "Warning: patient is recorded as LACTATING; this drug is on the pregnancy/lactation-risk list";
        }
        return null;
    }

    /** NB-111: correct rule set by tenant region (each region's own list): the region's dictionary
     * carries both the list and the English label. */
    static String controlledSubstanceWarning(SafetyDictionary dict, String drugName) {
        return matches(drugName, dict.controlled()) ? dict.controlledLabel().get("en") + " — additional documentation required" : null;
    }

    /** Stems: substring of the normalised name (the pre-CLN-004 rule); terms: whole words, as for allergy classes. */
    private static boolean matches(String drugName, SafetyDictionary.Terms list) {
        String lower = AllergyDrugClasses.normalise(drugName);
        return list.stems().stream().map(AllergyDrugClasses::normalise).anyMatch(lower::contains)
                || (!list.terms().isEmpty() && AllergyDrugClasses.containsWholeWord(lower, list.terms()));
    }
}
