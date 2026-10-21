package com.nabd.hms.shared.events.consumer;

import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.UUID;

/**
 * OPS-017: one consumer group on the Valkey event streams (docs/architecture/events.md section 7). Delivery is at least
 * once, so every consumer names an idempotency strategy; {@link ConsumerGroups} refuses to boot one without it.
 */
public interface EventConsumer {

    enum Idempotency {
        /** projector_applied_events row in the handler's own tenant transaction: exactly once in effect. */
        DB_LEDGER,
        /** Valkey key with a TTL, set after the handler succeeded: for stateless consumers (cache, SSE). */
        VALKEY_SET
    }

    /** Group name, e.g. projector, cache-invalidator. Lower case, digits and dashes. */
    String group();

    /** CloudEvents types handled. The streams read are ev:&lt;region&gt;:&lt;first segment of each type&gt;; other types are acked unhandled. */
    Set<String> types();

    Idempotency idempotency();

    /** State-carrying consumers: apply an event only if data.version is higher than the last applied for its subject (DB_LEDGER only). */
    default boolean versionGated() {
        return false;
    }

    /** DB_LEDGER: runs inside a transaction bound to event.tenantId() (TenantScope). Throw to retry. */
    void handle(Event event);

    /** One delivered CloudEvent. data is PHI-free by contract (events.md section 3). */
    record Event(String stream, String entryId, String id, String type, UUID tenantId, UUID subject, long version, JsonNode data) {
    }
}
