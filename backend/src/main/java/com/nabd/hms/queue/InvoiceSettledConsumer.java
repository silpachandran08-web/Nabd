package com.nabd.hms.queue;

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

/**
 * PLT-035: billing.invoice.settled.v1 -> the visit. Completes a visit still waiting at its terminal billing stop; the move publishes
 * visit.queue_entry.changed.v1, which the sse-fanout group turns into the realtime board delta. The outbox dispatcher calls it
 * without Valkey, the OPS-017 group "visit-settlement" with it (same shape as RxSignedWorklistConsumer). Idempotent: the DB ledger
 * on the event id, and the CAS move (an already completed visit is left alone).
 */
@Component
class InvoiceSettledConsumer implements OutboxEventHandler, EventConsumer {

    private final VisitApiService visits;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    InvoiceSettledConsumer(VisitApiService visits, TenantScope tenantScope, ObjectMapper json) {
        this.visits = visits;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    @Override
    public String eventType() {
        return DomainEventType.INVOICE_SETTLED.type();
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        JsonNode d = json.readTree(event.payloadJson());
        tenantScope.run(event.tenantId(), () -> apply(event.tenantId(), d));
    }

    @Override
    public String group() {
        return "visit-settlement";
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
        apply(event.tenantId(), event.data());
    }

    private void apply(UUID tenantId, JsonNode d) {
        visits.completeSettled(tenantId, UUID.fromString(d.path("settledBy").asString()), UUID.fromString(d.path("visitId").asString()));
    }
}
