package com.nabd.hms.organization.dpa.dto;

import java.time.Instant;
import java.util.UUID;

public record DpaOtpChallengeResponse(UUID otpChallengeId, Instant expiresAt) {
}
