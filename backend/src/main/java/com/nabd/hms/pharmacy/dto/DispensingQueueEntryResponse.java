package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DispensingQueueEntryResponse(UUID prescriptionId, UUID queueEntryId, UUID patientId, String patientName,
                                            String doctorName, Instant signedAt, int token, List<Item> items) {

    @Schema(name = "DispensingQueueItem")
    public record Item(String drugName, String dosage, String frequency, String duration, String instructions,
                       Integer stockQty) {
    }
}
