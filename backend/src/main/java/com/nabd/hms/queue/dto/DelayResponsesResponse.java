package com.nabd.hms.queue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-024: what the desk sees for one delay broadcast. {@code status} is queued, sent, delivered, held (20-minute rule), skipped_no_consent or failed. */
@Schema(name = "DelayResponsesResponse")
public record DelayResponsesResponse(List<Item> items, Map<String, Integer> counts) {

    @Schema(name = "DelayResponseItem")
    public record Item(UUID appointmentId, String displayName, String status, String response, Instant deliveredAt, Instant respondedAt) {
    }
}
