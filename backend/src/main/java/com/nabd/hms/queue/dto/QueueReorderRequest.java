package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

public record QueueReorderRequest(@NotNull Boolean priority, String reason) {
}
