package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PLT-013: ended emergency-access grants for the owner's review. deviceLabel is what the client called itself (display only).
 * outcome / reviewedAt are null while unreviewed; nextCursor is null on the last page. */
public record BreakGlassReviewQueueResponse(List<Item> items, String nextCursor) {
    public record Item(UUID grantId, Staff staff, String reasonCode, String reasonText, String deviceLabel, Instant startedAt,
                       Instant endedAt, int readsCount, String outcome, Instant reviewedAt) {
    }

    public record Staff(UUID id, String name) {
    }
}
