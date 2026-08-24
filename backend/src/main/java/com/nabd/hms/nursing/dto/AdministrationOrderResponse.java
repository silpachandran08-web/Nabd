package com.nabd.hms.nursing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdministrationOrderResponse(
        UUID id, UUID queueEntryId, UUID patientId, String patientName, String orderedByName, String drugName,
        String dose, String route, String site, String status, String recordedByName, String witnessedByName,
        String refuseReason, Instant recordedAt, Instant createdAt
) {
}
