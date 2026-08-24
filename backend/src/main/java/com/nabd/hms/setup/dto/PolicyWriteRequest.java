package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;

public record PolicyWriteRequest(
        @NotBlank String value
) {
}
