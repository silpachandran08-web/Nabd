package com.nabd.hms.nursing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcedureOrderResponse(
        UUID id, UUID queueEntryId, UUID patientId, String patientName, String orderedByName, String chargeCode,
        String chargeName, BigDecimal baseAmount, BigDecimal taxRatePercent, String prepNotes, String consentNote,
        String status, boolean billed, String completedByName, Instant completedAt, Instant createdAt,
        String consentSignedName, String consentRecordedByName, Instant consentSignedAt,
        // CLN-014: the signed form (null for a typed-name consent), a withdrawal, and the language the form opens in
        UUID consentId, String consentSha256, Instant consentWithdrawnAt, String consentWithdrawReason, String patientLanguage,
        // patientMinor: under 18 today (clinic date), null when the date of birth is unknown; guardianName: the linked guardian
        Boolean patientMinor, String guardianName
) {
}
