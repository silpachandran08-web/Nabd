package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

public record AllergyResponse(UUID id, UUID patientId, String substance, String severity, String reaction,
                               boolean active, UUID recordedBy, Instant recordedAt) {
}
