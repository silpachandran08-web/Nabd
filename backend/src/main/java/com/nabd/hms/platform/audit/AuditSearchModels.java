package com.nabd.hms.platform.audit;

import java.time.Instant;
import java.util.UUID;

final class AuditSearchModels {

    private AuditSearchModels() {
    }

    record AuditEntry(long id, UUID tenantId, String tenantName, String tenantSlug, String actorType,
                       UUID actorId, String actorName, String actorRole, String ipAddress, String action,
                       String entityType, UUID entityId, String before, String after, Instant createdAt) {
    }
}
