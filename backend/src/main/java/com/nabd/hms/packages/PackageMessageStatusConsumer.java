package com.nabd.hms.packages;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import com.nabd.hms.shared.notify.NotificationPort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

/** COM-044: comms.message.status_changed.v1 -> the delivery status of the package ledger event that asked for the message. The Comms
 * Gateway's events carry requestId; in-process sends are mapped by message id (NotificationPort.requestOfMessage). Messages of
 * other modules match no ledger row and change nothing. Outbox handler without Valkey, OPS-017 group "package-delivery" with it. */
@Component
class PackageMessageStatusConsumer implements OutboxEventHandler, EventConsumer {

    private final PackageMessagingService service;
    private final NotificationPort notifications;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    PackageMessageStatusConsumer(PackageMessagingService service, NotificationPort notifications, TenantScope tenantScope, ObjectMapper json) {
        this.service = service;
        this.notifications = notifications;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    @Override
    public String eventType() {
        return DomainEventType.MESSAGE_STATUS_CHANGED.type();
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        JsonNode d = json.readTree(event.payloadJson());
        tenantScope.run(event.tenantId(), () -> apply(d));
    }

    @Override
    public String group() {
        return "package-delivery";
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
        apply(event.data());
    }

    private void apply(JsonNode d) {
        String status = d.path("status").asString("");
        UUID request = d.hasNonNull("requestId") ? UUID.fromString(d.path("requestId").asString())
                : d.hasNonNull("messageId") ? notifications.requestOfMessage(UUID.fromString(d.path("messageId").asString())).orElse(null) : null;
        if (request != null && !status.isEmpty()) {
            service.deliveryChanged(request, status);
        }
    }
}
