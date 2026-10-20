package com.nabd.hms.organization.setup.dto;

import java.time.Instant;

public record SetupChecklistItemResponse(
        String step,
        String status,
        Instant skippedAt,
        Instant doneAt
) {
}
