package com.nabd.hms.pharmacy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

class PharmacyModels {

    record PharmacyItemRow(UUID id, String code, String name, boolean isRx, String hsnCode, BigDecimal price,
                            BigDecimal taxRatePercent, int stockQty, boolean active) {
    }

    record StockMovementRow(UUID id, String kind, int delta, int qtyBefore, int qtyAfter, String reason, UUID actorId,
                             String actorName, Instant occurredAt) {
    }

    record ActorInfo(String name, String role) {
    }

    /** NB-179: a signed prescription whose visit hasn't reached checkout yet. */
    record DispensingQueueRow(UUID prescriptionId, UUID queueEntryId, UUID patientId, String patientName,
                               String doctorName, Instant signedAt) {
    }

    record DispensingItemRow(String drugName, String dosage, String frequency, String duration, String instructions) {
    }
}
