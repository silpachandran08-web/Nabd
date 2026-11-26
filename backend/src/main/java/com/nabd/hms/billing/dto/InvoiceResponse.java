package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(UUID id, String invoiceNumber, UUID queueEntryId, UUID patientId, String patientName,
                               UUID doctorId, String doctorName, String currency, BigDecimal subtotal,
                               BigDecimal discount, BigDecimal tax, BigDecimal roundOff, BigDecimal total,
                               BigDecimal paid, BigDecimal balanceDue, String status,
                               List<LineItemResponse> lineItems, List<PaymentResponse> payments, Instant createdAt,
                               String documentTitle, String buyerGstin,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Insurance insurance) {

    /** The invoice of a visit that is not insured: everything as before FIN-036. */
    public InvoiceResponse(UUID id, String invoiceNumber, UUID queueEntryId, UUID patientId, String patientName, UUID doctorId, String doctorName,
                           String currency, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal roundOff, BigDecimal total, BigDecimal paid,
                           BigDecimal balanceDue, String status, List<LineItemResponse> lineItems, List<PaymentResponse> payments, Instant createdAt,
                           String documentTitle, String buyerGstin) {
        this(id, invoiceNumber, queueEntryId, patientId, patientName, doctorId, doctorName, currency, subtotal, discount, tax, roundOff, total, paid,
                balanceDue, status, lineItems, payments, createdAt, documentTitle, buyerGstin, null);
    }

    /**
     * FIN-036: present only on an insured visit's invoice (absent, not null, otherwise). total = patientShare + payerShare;
     * balanceDue is the patient's (patientShare - paid). pendingDecision is what the payer left unpaid after a payment: it waits
     * for a rejection / write-off decision and is never added to the patient's balance.
     */
    public record Insurance(UUID payerId, BigDecimal patientShare, BigDecimal payerShare, BigDecimal payerPaid, BigDecimal pendingDecision,
                            List<InsuranceLine> lines) {
    }

    /** One invoice line's split in minor units: patientShareMinor + payerShareMinor = the line's value after discount, tax included. */
    public record InsuranceLine(UUID lineId, long patientShareMinor, long payerShareMinor) {
    }
}
