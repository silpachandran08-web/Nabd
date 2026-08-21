package com.nabd.hms.clinical.dental.dto;

import java.time.Instant;
import java.util.UUID;

public record ToothResponse(UUID id, UUID patientId, int toothNumber, String status, String note,
                             UUID updatedBy, Instant updatedAt) {
}
