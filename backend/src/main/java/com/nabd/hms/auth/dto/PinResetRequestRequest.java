package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record PinResetRequestRequest(@NotBlank String tenantSlug, @NotBlank String mobilePhone) {
}
