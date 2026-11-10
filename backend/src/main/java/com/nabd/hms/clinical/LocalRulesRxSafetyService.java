package com.nabd.hms.clinical;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;

/** PLT-034: the local-rules RxSafetyService, moved unchanged out of PrescriptionService (rules in RxSafetyChecks). */
@Component
class LocalRulesRxSafetyService implements RxSafetyService {

    @Override
    public List<Finding> checkLine(String drugName, String brandName, DrugCoding.Codes codes, List<AllergyRow> allergies,
                                   PrescriptionRepository.PatientProfile profile, SafetyDictionary dict, LocalDate today) {
        List<Finding> found = new ArrayList<>(detect(drugName, allergies, profile, dict, today));
        // CLN-021: a coded allergy (ingredient or class id) matches a coded line by id, even when no dictionary text knows the brand.
        if (codes != null) {
            allergies.stream().filter(a -> (a.allergenIngredientId() != null && codes.ingredientIds().contains(a.allergenIngredientId()))
                            || (a.allergenClassId() != null && codes.classIds().contains(a.allergenClassId())))
                    .min(Comparator.comparingInt(a -> "severe".equals(a.severity()) ? 0 : "moderate".equals(a.severity()) ? 1 : 2))
                    .filter(a -> found.stream().noneMatch(f -> RxSafetyChecks.KIND_ALLERGY.equals(f.kind()) && RxSafetyService.sameMatch(f.matched(), a.substance())))
                    .ifPresent(a -> found.add(new Finding(RxSafetyChecks.KIND_ALLERGY, a.severity(),
                            a.substance() + " (" + a.severity() + ")", a.substance(), "severe".equals(a.severity()))));
        }
        if (brandName != null && !brandName.isBlank() && !brandName.trim().equalsIgnoreCase(drugName.trim())) {
            for (Finding d : detect(brandName, allergies, profile, dict, today)) {
                boolean dup = found.stream().anyMatch(f -> f.kind().equals(d.kind()) && RxSafetyService.sameMatch(f.matched(), d.matched()));
                if (!dup) {
                    found.add(d);
                }
            }
        }
        return found;
    }

    private List<Finding> detect(String drugName, List<AllergyRow> allergies, PrescriptionRepository.PatientProfile profile,
                                 SafetyDictionary dict, LocalDate today) {
        List<Finding> found = new ArrayList<>();
        findMatch(dict, allergies, drugName).ifPresent(a -> found.add(new Finding(RxSafetyChecks.KIND_ALLERGY, a.severity(),
                a.substance() + " (" + a.severity() + ")", a.substance(), "severe".equals(a.severity()))));
        String preg = profile == null ? null : RxSafetyChecks.pregnancyWarning(dict, drugName, profile.gender(), profile.dob(), today);
        // DOC-021: a recorded pregnant/lactating status escalates (severity, wording and a fresh decision: `matched`
        // differs from the generic one, so an earlier generic override does not carry over). Never weakens: any other
        // status falls through to the unchanged rule above.
        String recorded = profile == null ? null : RxSafetyChecks.recordedStatusWarning(dict, drugName, profile.pregnancyStatus());
        if (recorded != null) {
            found.add(new Finding(RxSafetyChecks.KIND_PREGNANCY, "severe", recorded, profile.pregnancyStatus(), true));
        } else if (preg != null) {
            found.add(new Finding(RxSafetyChecks.KIND_PREGNANCY, "caution", preg, "pregnancy/lactation", true));
        }
        String controlled = RxSafetyChecks.controlledSubstanceWarning(dict, drugName);
        if (controlled != null) {
            found.add(new Finding(RxSafetyChecks.KIND_CONTROLLED, "caution", controlled, "controlled substance", true));
        }
        return found;
    }

    /** The most severe matching allergy wins (severe, then moderate, then mild). */
    private static Optional<AllergyRow> findMatch(SafetyDictionary dict, List<AllergyRow> allergies, String drugName) {
        return allergies.stream().filter(a -> RxSafetyChecks.allergyMatches(dict, drugName, a.substance()))
                .min(Comparator.comparingInt(a -> switch (a.severity()) {
                    case "severe" -> 0;
                    case "moderate" -> 1;
                    default -> 2;
                }));
    }
}
