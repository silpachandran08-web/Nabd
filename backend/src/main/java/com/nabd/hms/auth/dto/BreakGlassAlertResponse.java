package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The calling Owner's own emergency-access alerts, newest first. Never carries patient data. */
public record BreakGlassAlertResponse(List<Item> items) {
    /** kind: activated (PLT-012) or review_reminder (PLT-013, the grant is still unreviewed 24 h after it ended). */
    public record Item(UUID grantId, String message, Instant createdAt, String kind) {
    }
}
