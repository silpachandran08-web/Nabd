package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.UUID;

/** A break-glass grant as the post-hoc review queue shows it — active or already ended. */
public record BreakGlassGrantResponse(UUID id, UUID staffId, String staffName, String reason, Instant activatedAt,
                                      Instant expiresAt, Instant deactivatedAt, String deactivatedReason) {
}
