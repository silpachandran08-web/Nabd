package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record BreakGlassResponse(UUID grantId, String staffName, String reasonCode, Instant activatedAt, Instant expiresAt) {
}
