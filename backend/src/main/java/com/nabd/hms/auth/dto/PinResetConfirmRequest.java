package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PinResetConfirmRequest(
        @NotBlank String tenantSlug,
        @NotBlank String mobilePhone,
        @NotBlank String token,
        @NotBlank @Pattern(regexp = "\\d{6,8}") String newPin
) {
}
