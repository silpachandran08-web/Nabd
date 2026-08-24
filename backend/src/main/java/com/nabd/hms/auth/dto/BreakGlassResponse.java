package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record BreakGlassResponse(UUID id, UUID staffId, String staffName, String reason, Instant activatedAt, Instant expiresAt) {
}
