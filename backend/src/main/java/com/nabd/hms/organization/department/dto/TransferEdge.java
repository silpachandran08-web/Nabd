package com.nabd.hms.department.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferEdge(@NotNull UUID fromDepartmentId, @NotNull UUID toDepartmentId) {
}
