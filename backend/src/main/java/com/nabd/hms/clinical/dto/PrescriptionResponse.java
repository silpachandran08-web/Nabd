package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** unresolvedWarnings: number of warnings that still need a recorded decision before this can be signed. */
public record PrescriptionResponse(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String status,
                                    Instant createdAt, Instant signedAt, List<PrescriptionItemResponse> items,
                                    int unresolvedWarnings) {
}
