package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ChargeHeadWriteRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String category,
        @NotNull @PositiveOrZero BigDecimal baseAmount,
        @PositiveOrZero BigDecimal followUpAmount,
        @PositiveOrZero BigDecimal emergencyAmount,
        String taxCode,
        @PositiveOrZero BigDecimal taxRatePercent,
        boolean doctorOverride,
        boolean active,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int displayOrder
) {
    public BigDecimal taxRatePercentOrZero() {
        return taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
    }
}
