package com.nabd.hms.patient.proxy;

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

/** IDC-014: visit.completed.v1 -> the visit instructions notice to the patient and record-scope caregivers. The outbox dispatcher calls it without Valkey, the OPS-017 group "care-visit-instructions" with it (same shape as
 * RxSignedWorklistConsumer). Idempotent: the DB ledger on the event id, and one notification dedupe key per event and recipient. */
@Component
class VisitInstructionsConsumer implements OutboxEventHandler, EventConsumer {

    private final CareFanoutService service;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    VisitInstructionsConsumer(CareFanoutService service, TenantScope tenantScope, ObjectMapper json) {
        this.service = service;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    @Override
    public String eventType() {
        return DomainEventType.VISIT_COMPLETED.type();
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        JsonNode d = json.readTree(event.payloadJson());
        tenantScope.run(event.tenantId(), () -> apply(d));
    }

    @Override
    public String group() {
        return "care-visit-instructions";
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
        if (d.hasNonNull("entryId")) {
            service.visitCompleted(UUID.fromString(d.path("entryId").asString()));
        }
    }
}
