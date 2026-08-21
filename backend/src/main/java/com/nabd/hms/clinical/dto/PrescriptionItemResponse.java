package com.nabd.hms.clinical.dto;

import java.util.UUID;

/** allergyWarning is recomputed fresh on every read against the patient's CURRENT active allergies
 * (NB-107) — null when no active allergy matches, "<substance> (<severity>)" when one does. Only a
 * "severe" match is ever blocking (see PrescriptionService); moderate/mild are warning-only. */
public record PrescriptionItemResponse(UUID id, String drugName, String dosage, String frequency, String duration,
                                        String instructions, String allergyOverrideReason, int displayOrder,
                                        String allergyWarning) {
}
