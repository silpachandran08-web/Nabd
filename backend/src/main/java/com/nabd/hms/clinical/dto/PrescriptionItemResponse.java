package com.nabd.hms.clinical.dto;

import java.util.UUID;

public record PrescriptionItemResponse(UUID id, String drugName, String dosage, String frequency, String duration,
                                        String instructions, String allergyOverrideReason, int displayOrder) {
}
