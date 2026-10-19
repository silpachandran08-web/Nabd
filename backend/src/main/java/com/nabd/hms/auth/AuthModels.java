package com.nabd.hms.auth;

import java.time.Instant;
import java.util.UUID;

/** Row shapes for the auth flow — plain records, no ORM ceremony for what's a handful of hand-written queries. */
final class AuthModels {
    private AuthModels() {
    }

    record Tenant(UUID id, String slug, String status, String name, String region) {
    }

    record Staff(UUID id, UUID tenantId, UUID roleId, String email, String name, String mobilePhone,
                 String pinHash, String status, String scope, boolean emailVerified, boolean mobileVerified,
                 boolean mfaEnabled, byte[] mfaSecretEnc, byte[] mfaPendingSecretEnc, boolean mfaResetRequired) {
    }

    /** builtIn = the clinic's one built-in role, the Owner (custom roles are never built-in, see RoleRepository). */
    record Role(UUID id, UUID tenantId, String name, String grantsJson, boolean mfaRequired, boolean builtIn) {
    }

    record SessionRow(UUID id, UUID tenantId, UUID staffId, UUID familyId, String tokenHash,
                       Instant expiresAt, Instant revokedAt, String revokedReason,
                       String deviceLabel, String ipAddress, Instant lastSeenAt) {
    }
}
