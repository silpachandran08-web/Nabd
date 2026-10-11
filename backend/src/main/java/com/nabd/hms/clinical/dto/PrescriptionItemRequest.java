package com.nabd.hms.clinical.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** overrides: the doctor's decisions for this line's warnings (may be null/empty). A decision already
 * recorded for the same drug and warning is kept when it is omitted, never silently cleared. */
public record PrescriptionItemRequest(@NotBlank String drugName, String dosage, String frequency, String duration,
                                       String instructions, @Valid List<RxWarningDecision> overrides) {
}
