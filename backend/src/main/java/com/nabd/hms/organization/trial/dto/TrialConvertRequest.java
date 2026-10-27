package com.nabd.hms.organization.trial.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** otpChallengeId + otp: the owner's step-up code (request one with POST /v1/setup/trial/convert/otp). */
public record TrialConvertRequest(@NotNull Boolean confirm, @NotNull UUID otpChallengeId, @NotBlank String otp) {
}
