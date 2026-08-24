package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DelayAnnounceRequest(@NotNull @Min(1) Integer delayMinutes, String reason) {
}
