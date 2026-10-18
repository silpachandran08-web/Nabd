package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DelayAnnounceRequest(@NotNull @Min(1) @Max(value = 240, message = "must be at most 240") Integer delayMinutes,
                                   @Size(max = 200) String reason) {
}
