package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Close a clinic day (FIN-005: date defaults to today; a past day can be closed or re-closed after a reopen).
 * note is required when the cash variance is beyond tolerance. */
public record DayCloseRequest(@NotNull @DecimalMin("0.00") BigDecimal countedCash, @Size(max = 1000) String note, LocalDate date) {
}
