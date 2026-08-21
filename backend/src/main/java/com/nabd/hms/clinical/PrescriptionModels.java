package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.UUID;

final class PrescriptionModels {

    private PrescriptionModels() {
    }

    record PrescriptionRow(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String status,
                            Instant createdAt, Instant signedAt) {
    }

    record PrescriptionItemRow(UUID id, UUID prescriptionId, String drugName, String dosage, String frequency,
                                String duration, String instructions, String allergyOverrideReason, int displayOrder) {
    }
}
