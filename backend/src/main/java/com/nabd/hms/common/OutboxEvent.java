package com.nabd.hms.common;

import java.util.UUID;

/** One claimed row from outbox_events (V44) — payloadJson is the raw jsonb column text,
 * left to each {@link OutboxEventHandler} to deserialize into its own payload type. */
public record OutboxEvent(long id, UUID tenantId, String eventType, String payloadJson, int attempts) {
}
