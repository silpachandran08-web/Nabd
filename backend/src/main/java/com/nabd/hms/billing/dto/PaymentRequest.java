package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull @Pattern(regexp = "cash|card|upi|other") String method,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount
) {
}
