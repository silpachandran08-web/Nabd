package com.nabd.hms.platform.tenant.dto;

import jakarta.validation.constraints.NotBlank;

public record TransitionRequest(
        @NotBlank String toStatus,
        @NotBlank String reason
) {
}
