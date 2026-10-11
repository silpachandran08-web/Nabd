package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.UUID;

final class AllergyModels {

    private AllergyModels() {
    }

    record AllergyRow(UUID id, UUID patientId, String substance, String severity, String reaction,
                       boolean active, UUID recordedBy, Instant recordedAt,
                       String category, String source, Instant updatedAt) {
    }

    /** One append-only history row; before/after are already-serialized JSON (nullable). */
    record AllergyEventRow(UUID id, UUID allergyId, String event, String beforeJson, String afterJson, String reason,
                            UUID actorId, Instant occurredAt) {
    }

    record AssessmentRow(UUID documentedBy, Instant documentedAt) {
    }

    record ActorInfo(String name, String role) {
    }
}
