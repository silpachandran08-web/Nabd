package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** allergyWarning / pregnancyWarning / controlledSubstanceWarning are the plain-text forms (kept for older
 * readers); warnings[] is the structured form with the decision state. All are recomputed fresh on every
 * read against the patient's CURRENT active allergies and profile (NB-107), for the generic name and the brand.
 * allergyOverrideReason is the label of the recorded allergy decision, if any.
 * CLN-021: drugId / genericName (snapshot taken when the line was saved) are null for an uncoded line (coded = false).
 * brandName, sigPattern, route, quantity, quantityUnit (DOC-033) are null on lines saved before they existed. */
public record PrescriptionItemResponse(UUID id, String drugName, String dosage, String frequency, String duration,
                                        String instructions, String allergyOverrideReason, int displayOrder,
                                        String allergyWarning, String pregnancyWarning, String controlledSubstanceWarning,
                                        List<RxWarningResponse> warnings,
                                        String brandName, String sigPattern, String route,
                                        BigDecimal quantity, String quantityUnit,
                                        String drugId, boolean coded, String genericName) {
}
