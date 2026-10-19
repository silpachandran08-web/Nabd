package com.nabd.hms.owner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** PLT-005: the owner's TOTP answer to the challenge token from /owners/auth/login. */
public record OwnerMfaVerifyRequest(@NotBlank String challengeToken, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
}
