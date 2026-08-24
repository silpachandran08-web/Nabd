package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.UUID;

final class TimelineModels {

    private TimelineModels() {
    }

    record EncounterRow(UUID queueEntryId, Instant occurredAt, UUID doctorId, String diagnosis,
                         String assessment, String medications) {
    }
}
