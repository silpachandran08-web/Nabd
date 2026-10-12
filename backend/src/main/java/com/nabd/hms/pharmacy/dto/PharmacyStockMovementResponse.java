package com.nabd.hms.pharmacy.dto;

import java.time.Instant;
import java.util.UUID;

/** One immutable stock movement: a manual adjustment or a checkout dispense. */
public record PharmacyStockMovementResponse(
        UUID id,
        String kind,
        int delta,
        int qtyBefore,
        int qtyAfter,
        String reason,
        UUID actorId,
        String actorName,
        Instant occurredAt
) {
}
