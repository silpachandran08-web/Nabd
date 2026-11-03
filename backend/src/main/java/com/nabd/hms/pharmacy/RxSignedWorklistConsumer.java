package com.nabd.hms.pharmacy;

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

/** PHL-012: clinical.rx.signed.v1 -> the pharmacy worklist. The outbox dispatcher calls it without Valkey, the OPS-017 group
 * "pharmacy-worklist" with it (same shape as NotificationRequestedHandler). Idempotent twice over: the DB ledger on the event id,
 * and the entry's unique prescription id. */
@Component
class RxSignedWorklistConsumer implements OutboxEventHandler, EventConsumer {

    private final PharmacyWorklistService service;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    RxSignedWorklistConsumer(PharmacyWorklistService service, TenantScope tenantScope, ObjectMapper json) {
        this.service = service;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    @Override
    public String eventType() {
        return DomainEventType.RX_SIGNED.type();
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        JsonNode d = json.readTree(event.payloadJson());
        tenantScope.run(event.tenantId(), () -> apply(d));
    }

    @Override
    public String group() {
        return "pharmacy-worklist";
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
        service.onRxSigned(UUID.fromString(d.path("prescriptionId").asString()), UUID.fromString(d.path("queueEntryId").asString()));
    }
}
