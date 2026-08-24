package com.nabd.hms.platform.billing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record DiscountRequestCreate(
        @NotNull UUID tenantId,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "100") BigDecimal percent,
        @NotBlank String reason
) {
}
