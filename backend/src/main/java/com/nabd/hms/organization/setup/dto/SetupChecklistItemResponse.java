package com.nabd.hms.organization.setup.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** status: pending, current (the first pending step), skipped or done. draft/version: ADM-007, the step's saved form and its optimistic version. */
public record SetupChecklistItemResponse(
        String step,
        String status,
        Instant skippedAt,
        Instant doneAt,
        JsonNode draft,
        int version,
        Instant updatedAt
) {
}
