package com.nabd.hms.messaging;

import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** COM-004 in-process consumer (S4) of comms.notification.requested.v1: the outbox dispatcher calls it without Valkey,
 * the OPS-017 group "comms-inprocess" with it (same shape as TenantConfigEvictions). In S5 the Comms Gateway replaces both. */
@Component
class NotificationRequestedHandler implements OutboxEventHandler, EventConsumer {

    private final NotificationService service;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    NotificationRequestedHandler(NotificationService service, TenantScope tenantScope, ObjectMapper json) {
        this.service = service;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    @Override
    public String eventType() {
        return DomainEventType.NOTIFICATION_REQUESTED.type();
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        UUID id = UUID.fromString(json.readTree(event.payloadJson()).path("requestId").asString());
        tenantScope.run(event.tenantId(), () -> service.deliver(id));
    }

    @Override
    public String group() {
        return "comms-inprocess";
    }

    @Override
    public Set<String> types() {
        return Set.of(eventType());
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.DB_LEDGER;
    }

    @Override
    public void handle(Event event) {
        service.deliver(UUID.fromString(event.data().path("requestId").asString()));
    }
}
