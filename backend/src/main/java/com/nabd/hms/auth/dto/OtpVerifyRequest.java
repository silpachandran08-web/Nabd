package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpVerifyRequest(
        @NotBlank String tenantSlug,
        @NotBlank String mobilePhone,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code
) {
}
