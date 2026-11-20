package com.nabd.hms.platform.tenant.dto;

import java.time.Instant;
import java.util.UUID;

public record LifecycleEventResponse(
        String fromStatus,
        String toStatus,
        UUID changedBy,
        String reason,
        Instant changedAt,
        // ADM-020 (additive): operator | staff | system, and the operator's name when an operator did it
        String actorType,
        String operatorName
) {
}
