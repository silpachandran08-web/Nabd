package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record PharmacyItemWriteRequest(
        @NotBlank String name,
        boolean isRx,
        String hsnCode,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero BigDecimal taxRatePercent,
        @PositiveOrZero Integer stockQty
) {
    public BigDecimal taxRatePercentOrZero() {
        return taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
    }

    public int stockQtyOrZero() {
        return stockQty == null ? 0 : stockQty;
    }
}
