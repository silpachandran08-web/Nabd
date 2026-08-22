package com.nabd.hms.staff;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class StaffRoleModels {
    private StaffRoleModels() {
    }

    record StaffRow(UUID id, UUID tenantId, UUID roleId, String email, String name, String mobilePhone,
                     String status, String scope, boolean emailVerified, boolean mobileVerified,
                     boolean mfaEnabled, List<String> fieldGrants, Instant lastSeenAt, Instant createdAt) {
    }

    record RoleRow(UUID id, UUID tenantId, String name, boolean builtIn, String grantsJson) {
    }

    /** NB-057: a bounded-time loan of another role's grants — e.g. covering a doctor's leave. */
    record DelegationRow(UUID id, UUID staffId, UUID delegatedRoleId, String delegatedRoleName, UUID grantedBy,
                          String reason, Instant startsAt, Instant expiresAt, Instant revokedAt, String revokedReason) {
        boolean active() {
            return revokedAt == null && expiresAt.isAfter(Instant.now());
        }
    }
}
