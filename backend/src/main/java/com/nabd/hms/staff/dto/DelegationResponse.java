package com.nabd.hms.staff.dto;

import java.time.Instant;
import java.util.UUID;

public record DelegationResponse(
        UUID id, UUID staffId, UUID delegatedRoleId, String delegatedRoleName, UUID grantedBy,
        String reason, Instant startsAt, Instant expiresAt, boolean active, Instant revokedAt, String revokedReason
) {
}
