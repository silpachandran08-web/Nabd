package com.nabd.hms.packages.dto;

import java.math.BigDecimal;
import java.util.List;

/** FIN-025: lines, refundMinor and owedMinor are the per-component refund; refundAmount and amountOwed are the same totals as amounts. */
public record RefundPreviewResponse(
        BigDecimal paid, BigDecimal usedListValue, BigDecimal refundAmount, BigDecimal amountOwed,
        List<RefundPreviewItem> items, List<RefundPreviewLine> lines, long refundMinor, long owedMinor
) {
}
