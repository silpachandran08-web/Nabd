package com.nabd.hms.common;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * NB-007: the one write path into the transactional outbox (outbox_events, V44). Call this from
 * inside the caller's own @Transactional business method — default REQUIRED propagation means
 * this joins that same transaction, so the event commits if and only if the caller's own write
 * does. A background dispatcher (OutboxDispatcher) delivers it later, with retries — see
 * OutboxEventHandler for the consumer side.
 */
@Component
public class EventPublisher {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    EventPublisher(JdbcTemplate jdbc, TenantContext tenantContext, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    @io.opentelemetry.instrumentation.annotations.WithSpan("outbox.publish")
    @Transactional
    public void publish(UUID tenantId, String eventType, Object payload) {
        tenantContext.set(tenantId);
        jdbc.update("INSERT INTO outbox_events (tenant_id, event_type, payload) VALUES (?,?,?::jsonb)",
                tenantId, eventType, toJson(payload));
    }

    /**
     * OPS-015: publishes a CloudEvents domain event (docs/architecture/events.md) in the caller's transaction. {@code data}
     * holds ids, status and codes only, never PHI (api/events/phi-denylist.txt); tenantId is added here and data.version
     * (= the outbox id, monotonic per aggregate) by the V1550 trigger. The envelope is rendered by view outbox_cloudevents.
     */
    @io.opentelemetry.instrumentation.annotations.WithSpan("outbox.publish")
    @Transactional
    public void publish(UUID tenantId, DomainEventType type, UUID aggregateId, java.util.Map<String, ?> data) {
        tenantContext.set(tenantId);
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("tenantId", tenantId);
        body.putAll(data);
        jdbc.update("INSERT INTO outbox_events (tenant_id, event_type, payload, ce_type, ce_source, aggregate_id, trace_parent) "
                        + "VALUES (?,?,?::jsonb,?,?,?,?)",
                tenantId, type.type(), toJson(body), type.type(), type.source(), aggregateId, TraceParent.current());
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("outbox event payload is not serializable", e);
        }
    }
}
