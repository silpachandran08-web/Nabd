package com.nabd.hms.packages.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(
        String id, String instanceId, String patientName, String packageName, String reason,
        BigDecimal usedListValue, BigDecimal refundAmount, BigDecimal amountOwed, String status,
        String creditNoteNumber, Instant createdAt
) {
}
