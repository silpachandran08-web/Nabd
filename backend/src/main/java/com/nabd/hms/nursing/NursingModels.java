package com.nabd.hms.nursing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class NursingModels {

    private NursingModels() {
    }

    record AdministrationOrderRow(UUID id, UUID queueEntryId, UUID patientId, UUID orderedBy, String drugName,
                                   String dose, String route, String site, Instant createdAt) {
    }

    record AdministrationRecordRow(String action, UUID recordedBy, UUID witnessedBy, String refuseReason,
                                    Instant recordedAt) {
    }

    record ProcedureOrderRow(UUID id, UUID queueEntryId, UUID patientId, UUID orderedBy, String chargeCode,
                              String chargeName, BigDecimal baseAmount, BigDecimal taxRatePercent, String prepNotes,
                              String consentNote, String status, boolean billed, UUID completedBy, Instant completedAt,
                              Instant createdAt, String consentSignedName, UUID consentRecordedBy, Instant consentSignedAt,
                              Instant consentWithdrawnAt, String consentWithdrawReason, UUID consentId, String consentSha256) {
    }

    record VaccineRecordRow(UUID id, UUID patientId, UUID queueEntryId, UUID recordedBy, String vaccineName, String dose,
                             String route, String site, String lotNumber, java.time.LocalDate expiryDate, String notes,
                             Instant administeredAt) {
    }

    record ActivityRow(String kind, String activity, UUID patientId, UUID staffId, Instant occurredAt) {
    }
}
