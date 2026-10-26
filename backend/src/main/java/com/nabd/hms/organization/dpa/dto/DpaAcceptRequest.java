package com.nabd.hms.organization.dpa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** eSignRef is reserved for Aadhaar eSign / Nafath / UAE Pass; those integrations are not built, so a value is refused. */
public record DpaAcceptRequest(@NotBlank String version, @NotNull UUID otpChallengeId,
                               @NotBlank @Pattern(regexp = "\\d{6}") String otp, String eSignRef) {
}
