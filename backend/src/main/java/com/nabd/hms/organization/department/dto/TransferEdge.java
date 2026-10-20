package com.nabd.hms.organization.department.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferEdge(@NotNull UUID fromDepartmentId, @NotNull UUID toDepartmentId) {
}
