package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record LineItemRequest(
        @NotBlank String chargeCode,
        @NotBlank String chargeName,
        @NotBlank String category,
        @Min(1) int quantity,
        @NotNull @DecimalMin(value = "0") BigDecimal unitPrice,
        @NotNull @DecimalMin(value = "0") BigDecimal taxRatePercent
) {
}
