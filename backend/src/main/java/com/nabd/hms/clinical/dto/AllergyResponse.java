package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

public record AllergyResponse(UUID id, UUID patientId, String substance, String severity, String reaction,
                               boolean active, UUID recordedBy, Instant recordedAt,
                               String category, String source, String recordedByName, Instant updatedAt,
                               String deactivatedReason, Instant deactivatedAt, String deactivatedByName,
                               boolean unconfirmed) { // ADM-012: imported from a legacy list, a doctor still has to confirm it
}
