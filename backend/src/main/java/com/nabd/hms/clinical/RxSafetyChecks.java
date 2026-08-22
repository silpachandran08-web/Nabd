package com.nabd.hms.clinical;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;

/**
 * NB-111/NB-113: same shape as PrescriptionService's existing allergy check — a small, named,
 * case-insensitive substring match against the free-text drug name, not a real drug database
 * (this app has no source for one, same limitation the allergy check already documents). Short,
 * illustrative lists of well-known examples, not a certified regulatory or clinical reference.
 */
final class RxSafetyChecks {

    private RxSafetyChecks() {
    }

    private static final List<String> PREGNANCY_RISK_DRUGS = List.of(
            "isotretinoin", "warfarin", "methotrexate", "misoprostol", "thalidomide",
            "valproate", "valproic", "tetracycline", "doxycycline", "lisinopril", "enalapril");

    private static final List<String> INDIA_SCHEDULE_H1_DRUGS = List.of(
            "tramadol", "alprazolam", "diazepam", "codeine", "buprenorphine", "zolpidem");

    private static final List<String> KSA_CONTROLLED_DRUGS = List.of(
            "tramadol", "alprazolam", "diazepam", "codeine", "morphine", "fentanyl");

    /** NB-113: fires for any patient of childbearing potential — female, roughly 12-55 — regardless of specialty. */
    static String pregnancyWarning(String drugName, String gender, LocalDate dob) {
        if (!"female".equals(gender)) {
            return null;
        }
        int age = Period.between(dob, LocalDate.now(ZoneOffset.UTC)).getYears();
        if (age < 12 || age > 55) {
            return null;
        }
        return matches(drugName, PREGNANCY_RISK_DRUGS)
                ? "Caution: avoid in pregnancy/lactation for patients of childbearing potential"
                : null;
    }

    /** NB-111: correct rule set by tenant region — India Schedule H1 vs KSA SFDA controlled list. */
    static String controlledSubstanceWarning(String drugName, String region) {
        List<String> list = "KSA".equals(region) ? KSA_CONTROLLED_DRUGS : INDIA_SCHEDULE_H1_DRUGS;
        String label = "KSA".equals(region) ? "SFDA-controlled substance" : "Schedule H1 controlled substance";
        return matches(drugName, list) ? label + " — additional documentation required" : null;
    }

    private static boolean matches(String drugName, List<String> keywords) {
        String lower = drugName.toLowerCase();
        return keywords.stream().anyMatch(lower::contains);
    }
}
