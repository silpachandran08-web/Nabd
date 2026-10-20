package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Reopen (unlock) a closed day — the reason is required and audited. date (FIN-005) wins over the ?date= query
 * parameter; both absent means today. */
public record DayReopenRequest(@NotBlank @Size(max = 500) String reason, LocalDate date) {
}
