package com.nabd.hms.realtime;

import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ACC-021: the 'sse-fanout' consumer group (OPS-017). Turns committed domain events into {type, id, v, fields} deltas on
 * the realtime topics. Idempotent on the CloudEvents id (Valkey key), so a redelivery never duplicates a delta; v is the
 * event's own monotonic version, so clients apply a delta only when v is newer. The event data is PHI-free by contract
 * and is passed through minus the tenant id: ids, statuses, tokens, versions, no names.
 * visit.eta.updated.v1 (ETA engine) has no producer yet; it is listed so it flows as soon as it exists.
 */
@Component
class SseFanoutConsumer implements EventConsumer {

    private record Route(List<RealtimeTopic> topics, String idField) {
    }

    private static final java.util.Map<String, Route> BASE_ROUTES = java.util.Map.of(
            "comms.message.status_changed.v1", new Route(List.of(RealtimeTopic.MESSAGES), "messageId"), // COM-006
            "visit.queue_entry.created.v1", new Route(List.of(RealtimeTopic.QUEUE, RealtimeTopic.NURSING), "entryId"),
            "visit.queue_entry.changed.v1", new Route(List.of(RealtimeTopic.QUEUE, RealtimeTopic.NURSING), "entryId"),
            "visit.eta.updated.v1", new Route(List.of(RealtimeTopic.QUEUE), "entryId"),
            "scheduling.doctor_status.changed.v1", new Route(List.of(RealtimeTopic.DOCTOR_STATUS), "doctorId"),
            "scheduling.doctor_delay.changed.v1", new Route(List.of(RealtimeTopic.DOCTOR_STATUS), "doctorId"),
            "scheduling.appointment.booked.v1", new Route(List.of(RealtimeTopic.REQUESTS), "appointmentId"),
            "scheduling.appointment.cancelled.v1", new Route(List.of(RealtimeTopic.REQUESTS), "appointmentId"),
            "scheduling.appointment.rescheduled.v1", new Route(List.of(RealtimeTopic.REQUESTS), "appointmentId"),
            "scheduling.booking.routed.v1", new Route(List.of(RealtimeTopic.REQUESTS), "appointmentId")); // ACC-009/010
    /** Map.of holds 10 pairs at most: later additions go here. ACC-025: a no-show is a change on the requests board. FIN-020: the checkout screen sees an invoice turn paid. */
    private static final Route BILLING_ROUTE = new Route(List.of(RealtimeTopic.BILLING), "invoiceId");
    private static final java.util.Map<String, Route> ROUTES;
    static {
        java.util.Map<String, Route> all = new java.util.HashMap<>(BASE_ROUTES);
        all.put("scheduling.appointment.no_show.v1", new Route(List.of(RealtimeTopic.REQUESTS), "appointmentId"));
        all.put("billing.payment.recorded.v1", BILLING_ROUTE);
        all.put("billing.invoice.paid.v1", BILLING_ROUTE);
        Route approvals = new Route(List.of(RealtimeTopic.BILLING), "approvalId"); // FIN-018: the desk banner and the owner inbox
        all.put("billing.discount.approval_requested.v1", approvals);
        all.put("billing.discount.approval_decided.v1", approvals);
        Route worklist = new Route(List.of(RealtimeTopic.PHARMACY_WORKLIST), "entryId"); // PHL-012: ids and status only
        all.put("pharmacy.worklist.entry_created.v1", worklist);
        all.put("pharmacy.worklist.status_changed.v1", worklist);
        ROUTES = java.util.Map.copyOf(all);
    }
    /** Revocation: these end the staff member's open streams (or the tenant's, for a role) so permissions are re-checked on reconnect. */
    private static final Set<String> CLOSERS = Set.of("iam.session.revoked.v1", "iam.staff.changed.v1", "iam.role.changed.v1");

    private final RealtimeHub hub;
    private final ObjectMapper json;

    SseFanoutConsumer(RealtimeHub hub, ObjectMapper json) {
        this.hub = hub;
        this.json = json;
    }

    @Override
    public String group() {
        return "sse-fanout";
    }

    @Override
    public Set<String> types() {
        Set<String> all = new java.util.HashSet<>(ROUTES.keySet());
        all.addAll(CLOSERS);
        return all;
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.VALKEY_SET;
    }

    @Override
    public void handle(Event e) {
        if (CLOSERS.contains(e.type())) {
            String staff = e.data().path("staffId").asString("");
            hub.closeStreams(e.tenantId(), staff.isEmpty() ? null : UUID.fromString(staff));
            return;
        }
        Route r = ROUTES.get(e.type());
        ObjectNode fields = json.createObjectNode();
        e.data().properties().forEach(en -> {
            if (!en.getKey().equals("tenantId") && !en.getKey().equals("version")) {
                fields.set(en.getKey(), en.getValue());
            }
        });
        String id = e.data().path(r.idField()).asString(e.subject() == null ? e.id() : e.subject().toString());
        for (RealtimeTopic t : r.topics()) {
            ObjectNode delta = json.createObjectNode().put("type", e.type()).put("id", id).put("v", e.version()).put("topic", t.wire());
            delta.set("fields", fields);
            hub.publish(e.tenantId(), t, delta.toString());
        }
    }
}
