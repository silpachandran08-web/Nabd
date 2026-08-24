package com.nabd.hms.staff.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record DelegationRequest(
        @NotNull UUID staffId,
        @NotNull UUID delegatedRoleId,
        @NotBlank String reason,
        @NotNull @Future Instant expiresAt
) {
}
