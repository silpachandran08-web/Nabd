package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.NotBlank;

public record PolicyWriteRequest(
        @NotBlank String value
) {
}
