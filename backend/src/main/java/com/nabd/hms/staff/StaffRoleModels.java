package com.nabd.hms.staff;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class StaffRoleModels {
    private StaffRoleModels() {
    }

    record StaffRow(UUID id, UUID tenantId, UUID roleId, String email, String name, String mobilePhone,
                     String status, String scope, boolean emailVerified, boolean mobileVerified,
                     boolean mfaEnabled, List<String> fieldGrants, UUID departmentId, Instant lastSeenAt, Instant createdAt,
                     String professionalRegistrationNo, boolean hasPin, boolean ownerLinked, boolean roleMfaRequired,
                     boolean clinician) {
    }

    record RoleRow(UUID id, UUID tenantId, String name, boolean builtIn, String grantsJson, boolean mfaRequired,
                   String presetCode, Integer presetVersion) {
    }

    /** NB-057: a bounded-time loan of another role's grants — e.g. covering a doctor's leave. */
    record DelegationRow(UUID id, UUID staffId, UUID delegatedRoleId, String delegatedRoleName, UUID grantedBy,
                          String reason, Instant startsAt, Instant expiresAt, Instant revokedAt, String revokedReason) {
        boolean active(Instant now) {
            return revokedAt == null && expiresAt.isAfter(now);
        }
    }
}
