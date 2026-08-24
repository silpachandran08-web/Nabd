package com.nabd.hms.platform;

import java.time.Instant;
import java.util.UUID;

/** Row shapes for platform-operator auth — mirrors AuthModels' style, master schema instead of public. */
final class PlatformAuthModels {
    private PlatformAuthModels() {
    }

    record Operator(UUID id, String name, String email, String pinHash, String role, String status,
                     boolean mfaEnabled, byte[] mfaSecretEnc) {
    }

    record SessionRow(UUID id, UUID operatorId, UUID familyId, String tokenHash,
                       Instant expiresAt, Instant revokedAt, String revokedReason,
                       String deviceLabel, String ipAddress, Instant lastSeenAt) {
    }
}
