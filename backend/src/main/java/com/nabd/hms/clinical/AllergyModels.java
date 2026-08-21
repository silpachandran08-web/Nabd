package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.UUID;

final class AllergyModels {

    private AllergyModels() {
    }

    record AllergyRow(UUID id, UUID patientId, String substance, String severity, String reaction,
                       boolean active, UUID recordedBy, Instant recordedAt) {
    }
}
