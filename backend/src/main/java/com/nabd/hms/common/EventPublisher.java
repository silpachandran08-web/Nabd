package com.nabd.hms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Transactional
    public void publish(UUID tenantId, String eventType, Object payload) {
        tenantContext.set(tenantId);
        jdbc.update("INSERT INTO outbox_events (tenant_id, event_type, payload) VALUES (?,?,?::jsonb)",
                tenantId, eventType, toJson(payload));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("outbox event payload is not serializable", e);
        }
    }
}
