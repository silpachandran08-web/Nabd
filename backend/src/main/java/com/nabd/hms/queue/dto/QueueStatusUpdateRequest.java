package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotBlank;

public record QueueStatusUpdateRequest(@NotBlank String status) {
}
