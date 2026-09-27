package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Close the clinic's today. note is required when the cash variance is beyond tolerance. */
public record DayCloseRequest(@NotNull @DecimalMin("0.00") BigDecimal countedCash, @Size(max = 1000) String note) {
}
