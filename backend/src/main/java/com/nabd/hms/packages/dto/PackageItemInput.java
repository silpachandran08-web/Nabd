package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PackageItemInput(
        @Pattern(regexp = "service_session|procedure|consultation|take_home_product") String itemType,
        @NotBlank String name,
        @Positive int quantity,
        @NotNull @DecimalMin(value = "0") BigDecimal unitListPrice,
        @DecimalMin(value = "0") BigDecimal taxRatePercent
) {
    public BigDecimal taxRatePercentOrZero() {
        return taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
    }
}
