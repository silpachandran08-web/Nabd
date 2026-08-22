package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PackageSettingsWriteRequest(
        @NotNull @DecimalMin(value = "0") @DecimalMax(value = "100") BigDecimal priceFloorPercent
) {
}
