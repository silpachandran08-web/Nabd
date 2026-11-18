package com.nabd.hms.packages;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

/** COM-044: billing.package.sold.v1 -> the package card message. The outbox dispatcher calls it without Valkey, the OPS-017 group
 * "package-card" with it (same shape as VisitInstructionsConsumer). Idempotent: one card event per instance, one dedupe key. */
@Component
class PackageSoldConsumer implements OutboxEventHandler, EventConsumer {

    private final PackageMessagingService service;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    PackageSoldConsumer(PackageMessagingService service, TenantScope tenantScope, ObjectMapper json) {
        this.service = service;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    @Override
    public String eventType() {
        return DomainEventType.PACKAGE_SOLD.type();
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        JsonNode d = json.readTree(event.payloadJson());
        tenantScope.run(event.tenantId(), () -> apply(d));
    }

    @Override
    public String group() {
        return "package-card";
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
        if (d.hasNonNull("packageInstanceId")) {
            service.sendCard(UUID.fromString(d.path("packageInstanceId").asString()));
        }
    }
}
