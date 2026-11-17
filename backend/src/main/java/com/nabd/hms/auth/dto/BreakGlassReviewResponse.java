package com.nabd.hms.auth.dto;

import java.time.Instant;

/** PLT-013: when the review was recorded, and whether clinical:breakGlass was removed from the holder. */
public record BreakGlassReviewResponse(Instant reviewedAt, boolean permissionRevoked) {
}
