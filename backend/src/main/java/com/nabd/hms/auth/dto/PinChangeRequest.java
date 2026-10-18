package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PinChangeRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{4,8}$") String currentPin,
        @NotBlank @Pattern(regexp = "^[0-9]{6,8}$") String newPin
) {
}
