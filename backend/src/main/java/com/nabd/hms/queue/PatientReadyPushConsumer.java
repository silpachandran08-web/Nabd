package com.nabd.hms.queue;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.organization.api.OrganizationConfig;
import com.nabd.hms.realtime.WebPushSender;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FEF-048: visit.queue_entry.changed.v1 -> a "patient ready" Web Push to the entry's doctor when the entry reaches the step right
 * before the consultation in its department's flow (waiting without vitals, vitals_done with them). The spec's
 * queue.entry.ready_for_doctor.v1 is this existing event, so no new event type. OPS-017 group "web-push" with Valkey, the outbox
 * dispatcher without it (same shape as RxSignedWorklistConsumer). Stateless (VALKEY_SET): a rare redelivery repeats a harmless push.
 */
@Component
class PatientReadyPushConsumer implements OutboxEventHandler, EventConsumer {

    private final QueueRepository repo;
    private final OrganizationConfig flows;
    private final TenantScope tenantScope;
    private final WebPushSender push;
    private final ObjectMapper json;

    PatientReadyPushConsumer(QueueRepository repo, OrganizationConfig flows, TenantScope tenantScope, WebPushSender push, ObjectMapper json) {
        this.repo = repo;
        this.flows = flows;
        this.tenantScope = tenantScope;
        this.push = push;
        this.json = json;
    }

    @Override
    public String eventType() {
        return DomainEventType.QUEUE_ENTRY_CHANGED.type();
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        apply(event.tenantId(), json.readTree(event.payloadJson()));
    }

    @Override
    public boolean callsExternalProvider() {
        return true; // the push service call runs outside the dispatcher's transaction
    }

    @Override
    public String group() {
        return "web-push";
    }

    @Override
    public Set<String> types() {
        return Set.of(eventType());
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.VALKEY_SET;
    }

    @Override
    public void handle(Event event) {
        apply(event.tenantId(), event.data());
    }

    private record Ready(UUID doctorId, int token) {
    }

    private void apply(UUID tenantId, JsonNode d) {
        String status = d.path("status").asString("");
        if (status.isEmpty() || !d.hasNonNull("entryId") || d.has("extendedMinutes")) return;
        UUID entryId = UUID.fromString(d.path("entryId").asString());
        Optional<Ready> ready = tenantScope.run(tenantId, () -> repo.findById(tenantId, entryId)
                .filter(e -> status.equals(e.status()) && readyForDoctor(flows.resolveStatusSequence(tenantId, e.departmentId()), status))
                .map(e -> new Ready(e.doctorId(), e.tokenNumber())));
        ready.ifPresent(r -> push.patientReady(tenantId, r.doctorId(), r.token())); // outside the transaction: HTTP to the push service
    }

    /** The status right before in_consult in this department's flow. */
    static boolean readyForDoctor(List<String> sequence, String status) {
        int i = sequence.indexOf(status);
        return i >= 0 && i + 1 < sequence.size() && "in_consult".equals(sequence.get(i + 1));
    }
}
