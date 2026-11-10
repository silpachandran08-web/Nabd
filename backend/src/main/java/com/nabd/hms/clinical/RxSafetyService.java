package com.nabd.hms.clinical;

import java.time.LocalDate;
import java.util.List;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;

/**
 * PLT-034 / ADR-13: the port PrescriptionService asks for a line's safety findings (allergy, pregnancy/lactation,
 * controlled substance). Pure: no I/O, no decisions; the caller loads the inputs and owns the decision/sign flow.
 * LocalRulesRxSafetyService is the only implementation (the region safety dictionary plus coded allergy ids); a drug
 * knowledge-base adapter would be a second one.
 */
public interface RxSafetyService {

    /** One finding on a line. requiresDecision = signing is blocked until a named reason is recorded. */
    record Finding(String kind, String severity, String message, String matched, boolean requiresDecision) {
    }

    /** DOC-033: the checks run on the generic text AND the brand text (and CLN-021 coded ids); findings are not duplicated. */
    List<Finding> checkLine(String drugName, String brandName, DrugCoding.Codes codes, List<AllergyRow> allergies,
                            PrescriptionRepository.PatientProfile profile, SafetyDictionary dict, LocalDate today);

    /** Two findings (or a finding and a stored decision) are about the same thing: case- and space-insensitive. */
    static boolean sameMatch(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
