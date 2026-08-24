package com.nabd.hms.platform.tenant.dto;

import java.time.Instant;
import java.util.UUID;

public record LifecycleEventResponse(
        String fromStatus,
        String toStatus,
        UUID changedBy,
        String reason,
        Instant changedAt
) {
}
