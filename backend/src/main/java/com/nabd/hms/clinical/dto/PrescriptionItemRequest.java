package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;

public record PrescriptionItemRequest(@NotBlank String drugName, String dosage, String frequency, String duration,
                                       String instructions, String allergyOverrideReason) {
}
