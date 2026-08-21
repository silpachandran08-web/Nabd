package com.nabd.hms.clinical.dental;

import java.time.Instant;
import java.util.UUID;

final class DentalModels {

    private DentalModels() {
    }

    record ToothRow(UUID id, UUID patientId, int toothNumber, String status, String note, UUID updatedBy,
                     Instant updatedAt) {
    }
}
