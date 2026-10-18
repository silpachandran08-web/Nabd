package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record BreakGlassActivateResponse(UUID grantId, Instant expiresAt, String accessToken, String refreshToken) {
}
