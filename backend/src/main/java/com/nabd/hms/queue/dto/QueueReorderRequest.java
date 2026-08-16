package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QueueReorderRequest(@NotNull Boolean priority, @NotBlank @Size(min = 3) String reason) {
}
