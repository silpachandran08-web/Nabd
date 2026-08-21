package com.nabd.hms.platform.access.dto;

import java.time.Instant;
import java.util.UUID;

public record GrantResponse(
        UUID id,
        UUID tenantId,
        UUID operatorId,
        String operatorName,
        String operatorRole,
        String reason,
        Instant grantedAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean active
) {
}
