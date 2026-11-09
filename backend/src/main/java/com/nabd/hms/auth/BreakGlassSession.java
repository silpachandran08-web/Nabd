package com.nabd.hms.auth;

import java.time.Instant;
import java.util.UUID;

/** The elevated session a break-glass token is minted for (TokenService.mintTokenPair). */
public record BreakGlassSession(UUID grantId, UUID sessionId, Instant expiresAt) {
}
