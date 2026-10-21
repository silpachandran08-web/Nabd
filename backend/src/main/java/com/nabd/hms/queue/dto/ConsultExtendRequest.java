package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

public record ConsultExtendRequest(@NotNull Integer minutes) {
}
