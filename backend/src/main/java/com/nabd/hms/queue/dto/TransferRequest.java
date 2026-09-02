package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferRequest(@NotNull UUID toDepartmentId, @NotNull UUID doctorId) {
}
