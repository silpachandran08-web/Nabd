package com.nabd.hms.nursing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaccineRecordResponse(
        UUID id, UUID patientId, String patientName, UUID queueEntryId, String vaccineName, String dose, String route,
        String site, String lotNumber, LocalDate expiryDate, String notes, String recordedByName, Instant administeredAt
) {
}
