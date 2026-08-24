package com.nabd.hms.packages.dto;

import java.math.BigDecimal;
import java.util.List;

public record RefundPreviewResponse(
        BigDecimal paid, BigDecimal usedListValue, BigDecimal refundAmount, BigDecimal amountOwed,
        List<RefundPreviewItem> items
) {
}
