package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoveryVerifyRequest(@NotBlank String challengeToken, @NotBlank @Size(max = 32) String recoveryCode) {
}
