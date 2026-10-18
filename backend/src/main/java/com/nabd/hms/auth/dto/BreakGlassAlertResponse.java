package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The calling Owner's own emergency-access alerts, newest first. Never carries patient data. */
public record BreakGlassAlertResponse(List<Item> items) {
    public record Item(UUID grantId, String message, Instant createdAt) {
    }
}
