package com.nabd.hms.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class CheckoutModels {

    private CheckoutModels() {
    }

    record ChargeRow(UUID id, String code, String name, String category, BigDecimal baseAmount,
                      BigDecimal followUpAmount, BigDecimal taxRatePercent) {
    }

    record QueueEntryContext(UUID patientId, UUID doctorId, String queueStatus, boolean hasAppointment) {
    }

    record InvoiceRow(UUID id, String invoiceNumber, UUID queueEntryId, UUID patientId, UUID doctorId,
                       BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal roundOff,
                       BigDecimal total, BigDecimal paid, String status, Instant createdAt) {
    }

    record LineItemRow(String chargeCode, String chargeName, String category, int quantity,
                        BigDecimal unitPrice, BigDecimal taxRatePercent, BigDecimal lineTotal) {
    }

    record PaymentRow(UUID id, String method, BigDecimal amount, Instant recordedAt) {
    }

    record LineItemInput(String chargeCode, String chargeName, String category, int quantity,
                          BigDecimal unitPrice, BigDecimal taxRatePercent) {
    }

    /** NB-179: what a signed-but-not-yet-billed prescription still needs dispensed. */
    record PrescribedItemRow(String drugName, String dosage, String frequency, String duration, String instructions) {
    }
}
