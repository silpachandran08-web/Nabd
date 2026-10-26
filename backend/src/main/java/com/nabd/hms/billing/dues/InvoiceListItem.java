package com.nabd.hms.billing.dues;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** FIN-014: one row of the invoice list. balanceDue already subtracts write-offs. */
@Schema(name = "InvoiceListItem")
public record InvoiceListItem(UUID id, String invoiceNumber, UUID patientId, String patientName, UUID doctorId, String status,
                              BigDecimal total, BigDecimal paid, BigDecimal writtenOff, BigDecimal balanceDue, Instant createdAt) {
}
