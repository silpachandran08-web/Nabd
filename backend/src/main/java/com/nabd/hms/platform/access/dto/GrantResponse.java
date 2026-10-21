package com.nabd.hms.platform.access.dto;

import java.time.Instant;
import java.util.UUID;

/** status: requested | active | declined | expired | revoked. expiresAt: request deadline while requested, window end once active. */
public record GrantResponse(
        UUID id,
        UUID tenantId,
        UUID ticketId,
        String scope,
        UUID operatorId,
        String operatorName,
        String operatorRole,
        String reason,
        Instant grantedAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean active,
        String status,
        Integer minutes,
        Instant windowStartedAt,
        Instant windowEndsAt,
        String declineNote
) {
}
