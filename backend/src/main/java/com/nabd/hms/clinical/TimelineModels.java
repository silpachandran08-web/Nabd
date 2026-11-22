package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.UUID;

final class TimelineModels {

    private TimelineModels() {
    }

    /** id = the visit's queue entry, or the document id of an imported past visit (kind 'imported_history'). */
    record EncounterRow(UUID id, Instant occurredAt, UUID doctorId, String diagnosis,
                         String assessment, String medications, String kind, String doctorName, String bill) {
    }
}
