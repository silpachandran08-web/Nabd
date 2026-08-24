package com.nabd.hms.platform.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RequestGrantRequest(
        @NotNull UUID tenantId,
        @NotBlank @Size(max = 500) String reason
) {
}
