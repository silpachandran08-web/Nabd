package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

public record NoteResponse(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String subjective,
                            String objective, String assessment, String plan, String status,
                            Instant signedAt, Instant updatedAt) {
}
