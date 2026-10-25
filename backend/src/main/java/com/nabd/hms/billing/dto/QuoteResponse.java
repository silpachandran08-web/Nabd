package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.util.List;

/** FIN-008: all amounts in the currency's minor unit. */
public record QuoteResponse(String currency, String documentType, List<Line> lines, QuoteTotals totals) {
    public record Line(String chargeCode, long discountMinor, long taxableMinor, List<Tax> taxes) {
    }

    public record Tax(String type, BigDecimal rate, long amountMinor) {
    }

    public record QuoteTotals(long subtotalMinor, long discountMinor, long taxMinor, long roundOffMinor, long totalMinor) {
    }
}
