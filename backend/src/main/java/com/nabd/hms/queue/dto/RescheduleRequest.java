package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RescheduleRequest(@NotNull Instant newStartTime) {
}
