package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** ACC-024: PATCH /doctors/{id}/delay/{delayId}. */
public record DelayChangeRequest(@NotNull @Min(1) @Max(value = 240, message = "must be at most 240") Integer minutes) {
}
