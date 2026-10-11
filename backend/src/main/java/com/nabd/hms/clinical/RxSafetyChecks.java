package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.RxReasonOption;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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

    /** Drug-class membership for allergies recorded by class ("Penicillin", "Sulfa", "NSAID"): illustrative,
     * short, not a drug database (same limitation as the lists above). A class allergy flags these names. */
    private static final Map<String, List<String>> ALLERGY_CLASSES = Map.of(
            "penicillin", List.of("amoxicillin", "amoxycillin", "ampicillin", "flucloxacillin", "cloxacillin", "piperacillin",
                    "phenoxymethylpenicillin", "benzylpenicillin", "co-amoxiclav", "amoxiclav", "augmentin"),
            "sulfa", List.of("sulfamethoxazole", "sulphamethoxazole", "cotrimoxazole", "co-trimoxazole", "trimethoprim-sulfa",
                    "sulfasalazine", "sulfadiazine", "bactrim"),
            "nsaid", List.of("ibuprofen", "diclofenac", "naproxen", "ketorolac", "indomethacin", "mefenamic", "aspirin", "etoricoxib",
                    "celecoxib", "piroxicam"));

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

    /** True when the (lower-cased) drug name contains the recorded substance, or the substance is a class
     * (penicillin / sulfa / NSAID) and the drug is a known member of it. */
    static boolean allergyMatches(String drugName, String substance) {
        String drug = drugName.toLowerCase().trim();
        String sub = substance.toLowerCase().trim();
        if (sub.isEmpty()) {
            return false;
        }
        if (drug.contains(sub)) {
            return true;
        }
        return ALLERGY_CLASSES.entrySet().stream()
                .anyMatch(e -> sub.contains(e.getKey()) && e.getValue().stream().anyMatch(drug::contains));
    }

    /** NB-113: fires for any patient of childbearing potential — female, roughly 12-55 — regardless of specialty. */
    static String pregnancyWarning(String drugName, String gender, LocalDate dob, LocalDate today) {
        if (!"female".equals(gender)) {
            return null;
        }
        int age = Period.between(dob, today).getYears();
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
