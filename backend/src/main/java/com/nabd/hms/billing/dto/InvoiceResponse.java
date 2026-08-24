package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(UUID id, String invoiceNumber, UUID queueEntryId, UUID patientId, String patientName,
                               UUID doctorId, String doctorName, String currency, BigDecimal subtotal,
                               BigDecimal discount, BigDecimal tax, BigDecimal roundOff, BigDecimal total,
                               BigDecimal paid, BigDecimal balanceDue, String status,
                               List<LineItemResponse> lineItems, List<PaymentResponse> payments, Instant createdAt) {
}
