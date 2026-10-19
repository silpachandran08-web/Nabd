package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaConfirmRequest(
        @NotBlank @Pattern(regexp = "\\d{6}") String code,
        String setupToken // typ=mfa_setup token from login; omitted for an access session
) {
}
