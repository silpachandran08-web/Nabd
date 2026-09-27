package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reopen (unlock) a closed day — the reason is required and audited. */
public record DayReopenRequest(@NotBlank @Size(max = 500) String reason) {
}
