package com.nabd.hms.platform.audit.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record AuditEntryResponse(
        long id,
        UUID tenantId,
        String tenantName,
        String tenantSlug,
        String actorType,
        UUID actorId,
        String actorName,
        String actorRole,
        String ipAddress,
        String action,
        String entityType,
        UUID entityId,
        JsonNode before,
        JsonNode after,
        Instant createdAt
) {
}
