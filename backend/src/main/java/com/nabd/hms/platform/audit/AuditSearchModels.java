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

    /** Every field optional (null = no filter). Shared by the paged view and the CSV export so the two can
     * never disagree about what "the filtered log" means. actor* are PLT-028's additive actor filter (V85). */
    record AuditFilter(UUID tenantId, String action, String entityType, Instant createdAfter, Instant createdBefore,
                       UUID actorId, String actorType, String actorRole, String actorName) {
    }

    record OperatorInfo(String name, String role) {
    }
}
