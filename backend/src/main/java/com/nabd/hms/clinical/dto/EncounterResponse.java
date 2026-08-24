package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

public record EncounterResponse(UUID queueEntryId, Instant occurredAt, UUID doctorId, String diagnosis,
                                 String assessment, String medications) {
}
