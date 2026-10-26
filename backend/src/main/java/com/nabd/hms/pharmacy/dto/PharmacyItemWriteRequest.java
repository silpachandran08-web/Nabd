package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PharmacyItemWriteRequest(
        @NotBlank String name,
        boolean isRx,
        String hsnCode,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero BigDecimal taxRatePercent,
        @PositiveOrZero Integer stockQty,
        /** PHL-002: must be valid for the tenant's market (422 schedule-not-valid-for-market). Omitted = unchanged (or derived from isRx on create). */
        @Size(max = 40) String scheduleClass,
        /** PHL-002: drug master product to link; its schedule flag sets the class unless scheduleClass is also sent. */
        @Size(max = 80) String drugMasterId,
        @Size(max = 200) String genericName,
        @Positive Integer packSize,
        @Size(max = 40) String unit,
        /** PHL-004: false = bought outside. Omitted = unchanged (true on create). */
        Boolean inHouse
) {
    public BigDecimal taxRatePercentOrZero() {
        return taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
    }

    public int stockQtyOrZero() {
        return stockQty == null ? 0 : stockQty;
    }
}
