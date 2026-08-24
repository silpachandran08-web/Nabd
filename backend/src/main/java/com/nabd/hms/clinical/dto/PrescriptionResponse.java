package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PrescriptionResponse(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String status,
                                    Instant createdAt, Instant signedAt, List<PrescriptionItemResponse> items) {
}
