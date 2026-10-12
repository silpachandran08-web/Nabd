package com.nabd.hms.clinical.dto;

import java.util.List;
import java.util.UUID;

/** allergyWarning / pregnancyWarning / controlledSubstanceWarning are the plain-text forms (kept for older
 * readers); warnings[] is the structured form with the decision state. All are recomputed fresh on every
 * read against the patient's CURRENT active allergies and profile (NB-107). allergyOverrideReason is the label
 * of the recorded allergy decision, if any. */
public record PrescriptionItemResponse(UUID id, String drugName, String dosage, String frequency, String duration,
                                        String instructions, String allergyOverrideReason, int displayOrder,
                                        String allergyWarning, String pregnancyWarning, String controlledSubstanceWarning,
                                        List<RxWarningResponse> warnings) {
}
