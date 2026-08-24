package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record MfaVerifyRequest(
        // not @NotBlank: the step-up branch (an authenticated caller re-proving MFA) ignores this
        // field entirely — the caller's own access token is the identity, not a challenge JWT.
        @NotNull String challengeId,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code
) {
}
