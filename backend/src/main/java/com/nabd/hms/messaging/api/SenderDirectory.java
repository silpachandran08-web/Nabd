package com.nabd.hms.messaging.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * COM-022: what the owner page and the platform fleet view read from the Comms Gateway (/internal/v1/senders/**). The gateway owns the data;
 * Core checks the permission and passes the clinic it resolved. Every method fails with a 503 ApiException when the gateway is not configured or down.
 */
public interface SenderDirectory {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "SenderEvent")
    record Event(Instant at, String kind, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "MessagingSender")
    record Sender(UUID id, String kind, String displayPhone, String displayName, String status, String rating, String quality, String tier,
                  String tierLabel, String throttleMode, String throttleReason, Instant overrideUntil, Double blockRate24h,
                  List<Double> blockRate7d, double throttleLine, List<Event> events) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "MessagingTemplate")
    record Template(UUID id, String name, String language, String category, String status, String rejectionReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "MessagingHealth")
    record Health(List<Sender> senders, List<Template> templates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "FleetSender")
    record FleetItem(UUID senderAccountId, UUID tenantId, String tenantName, String kind, String displayPhone, String region, String status,
                     String rating, String tier, String throttleMode, Double deliveredRate7d, long sends7d) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "FleetSenderPage")
    record FleetPage(List<FleetItem> items, String nextCursor) {}

    Health health(UUID tenantId);

    /** High / Medium / Low / Unknown, or null when the clinic has no number. Safe for every role (no detail). */
    String quality(UUID tenantId);

    FleetPage fleet(String rating, String region, String cursor, int limit);
}
