package com.nabd.hms.pharmacy.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DispensingQueueEntryResponse(UUID prescriptionId, UUID queueEntryId, UUID patientId, String patientName,
                                            String doctorName, Instant signedAt, List<Item> items) {

    public record Item(String drugName, String dosage, String frequency, String duration, String instructions) {
    }
}
