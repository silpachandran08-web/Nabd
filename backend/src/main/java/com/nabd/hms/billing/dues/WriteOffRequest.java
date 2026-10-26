package com.nabd.hms.billing.dues;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(name = "WriteOffRequest")
public record WriteOffRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank @Size(max = 40) String reasonCode, @Size(max = 500) String note) {
}
