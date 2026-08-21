package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.UUID;

final class NoteModels {

    private NoteModels() {
    }

    record NoteRow(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String subjective,
                    String objective, String assessment, String plan, String diagnosis, String status,
                    Instant signedAt, Instant updatedAt) {
    }

    /** The queue entry's own patient/doctor — looked up server-side, never trusted from the client. */
    record QueueEntryOwner(UUID patientId, UUID doctorId) {
    }
}
