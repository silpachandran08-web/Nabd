package com.nabd.hms.clinical;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class VitalsModels {

    private VitalsModels() {
    }

    record VitalsRow(UUID id, UUID queueEntryId, UUID patientId, BigDecimal heightCm, BigDecimal weightKg,
                      Integer bpSystolic, Integer bpDiastolic, Integer pulseBpm, BigDecimal tempCelsius,
                      Integer spo2Percent, UUID recordedBy, Instant recordedAt) {
    }

    /** Patient id + current queue status — status decides whether recording vitals should also
     * advance the queue (only meaningful once, from vitals_pending; a later correction shouldn't
     * attempt an illegal same-state transition). */
    record QueueEntrySnapshot(UUID patientId, String status) {
    }
}
