package com.nabd.hms.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class CheckoutModels {

    private CheckoutModels() {
    }

    record ChargeRow(UUID id, String code, String name, String category, BigDecimal baseAmount,
                      BigDecimal followUpAmount, BigDecimal taxRatePercent, Integer stockQty, boolean isRx) {
    }

    /** V3-004 / QA-OI-2: the authoritative catalogue row behind a charge code (stocked = pharmacy item). */
    record PharmacyChargeRow(UUID id, String name, String category, BigDecimal baseAmount, BigDecimal followUpAmount, BigDecimal taxRatePercent,
                              boolean active, boolean doctorOverride, boolean stocked, boolean isRx, BigDecimal emergencyAmount) {
    }

    record VoidableInvoice(String invoiceNumber, String status, java.math.BigDecimal paid) {
    }

    record QueueEntryContext(UUID patientId, UUID doctorId, UUID departmentId, String queueStatus, boolean hasAppointment) {
    }

    record InvoiceRow(UUID id, String invoiceNumber, UUID queueEntryId, UUID patientId, UUID doctorId,
                       BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal roundOff,
                       BigDecimal total, BigDecimal paid, String status, Instant createdAt) {
    }

    record LineItemRow(String chargeCode, String chargeName, String category, int quantity,
                        BigDecimal unitPrice, BigDecimal taxRatePercent, BigDecimal lineTotal,
                        BigDecimal listUnitPrice, String tier, String overrideReasonCode,
                        UUID id, String taxCategory, boolean taxInclusive, boolean interState, String sacHsn, Long taxableMinor) {
    }

    record PaymentRow(UUID id, String method, BigDecimal amount, Instant recordedAt, String idempotencyKey) {
    }

    /** FIN-003: a fully server-resolved line (price provenance included). */
    record LineItemInput(String chargeCode, String chargeName, String category, int quantity,
                          BigDecimal unitPrice, BigDecimal taxRatePercent, UUID catalogueId, BigDecimal listUnitPrice,
                          String tier, String overrideReasonCode, String overrideNote, UUID overriddenBy,
                          boolean isRx, UUID prescriptionItemId, String shortfallReason) {
        /** FIN-008: the tax engine decides the rate (head class, effective-dated rule); the line then stores that rate. */
        LineItemInput withTaxRate(BigDecimal rate) {
            return new LineItemInput(chargeCode, chargeName, category, quantity, unitPrice, rate, catalogueId, listUnitPrice, tier,
                    overrideReasonCode, overrideNote, overriddenBy, isRx, prescriptionItemId, shortfallReason);
        }
    }

    /** NB-179: what a signed-but-not-yet-billed prescription still needs dispensed. */
    record PrescribedItemRow(UUID id, String drugName, String dosage, String frequency, String duration, String instructions,
                             BigDecimal quantity, BigDecimal dispensed) {
    }
}
