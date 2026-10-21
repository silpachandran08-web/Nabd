package com.nabd.hms.platform.access.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the clinic owner sees of a support access request: who, why, what scope, how long, and where it stands. */
public record OwnerRequestResponse(List<Item> items) {

    @io.swagger.v3.oas.annotations.media.Schema(name = "SupportAccessRequestItem")
    public record Item(UUID id, String operatorName, String reason, String scope, Integer minutes, String status,
                       Instant requestedAt, Instant expiresAt, Instant windowStartedAt, Instant windowEndsAt,
                       String declineNote) {
    }
}
