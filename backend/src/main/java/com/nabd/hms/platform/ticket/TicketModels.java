package com.nabd.hms.platform.ticket;

import java.time.Instant;
import java.util.UUID;

final class TicketModels {

    private TicketModels() {
    }

    record Ticket(UUID id, UUID tenantId, String tenantName, String tenantSlug, String source,
                  UUID raisedByStaffId, String raisedByName, String raisedByEmail, String raisedByRole,
                  String subject, String description, String priority, String status, Instant slaDueAt,
                  Instant resolvedAt, Instant createdAt) {
    }

    /** Who's actually raising it — resolved from the caller's staff row before the insert. */
    record Raiser(UUID staffId, String name, String email, String role) {
    }
}
