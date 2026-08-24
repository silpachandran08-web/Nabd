package com.nabd.hms.platform.ticket.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        UUID tenantId,
        String tenantName,
        String tenantSlug,
        String source,
        String raisedByName,
        String raisedByEmail,
        String raisedByRole,
        String subject,
        String description,
        String priority,
        String status,
        Instant slaDueAt,
        boolean slaBreached,
        Instant resolvedAt,
        Instant createdAt
) {
}
