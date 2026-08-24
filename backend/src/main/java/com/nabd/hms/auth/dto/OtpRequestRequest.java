package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OtpRequestRequest(@NotBlank String tenantSlug, @NotBlank String mobilePhone) {
}
