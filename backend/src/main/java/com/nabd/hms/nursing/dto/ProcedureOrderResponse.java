package com.nabd.hms.nursing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcedureOrderResponse(
        UUID id, UUID queueEntryId, UUID patientId, String patientName, String orderedByName, String chargeCode,
        String chargeName, BigDecimal baseAmount, BigDecimal taxRatePercent, String prepNotes, String consentNote,
        String status, boolean billed, String completedByName, Instant completedAt, Instant createdAt
) {
}
