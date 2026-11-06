package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record MfaVerifyRequest(
        // not @NotBlank: the step-up branch (an authenticated caller re-proving MFA) ignores this
        // field entirely — the caller's own access token is the identity, not a challenge JWT.
        @NotNull String challengeId,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
        // IDC-013: step-up branch only. The one object this confirmation is for (e.g. a proxy id); the token then carries it as
        // "target" plus a jti, and the endpoint consumes it once (StepUpVerifier.requireFor). Absent for the older step-up gates.
        @jakarta.validation.constraints.Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_:-]*$") String target
) {
}
