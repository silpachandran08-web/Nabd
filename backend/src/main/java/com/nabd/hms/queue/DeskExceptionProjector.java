package com.nabd.hms.queue;

import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ACC-032: the 'desk-exceptions' consumer group (DB ledger: exactly once in effect, inside the event's tenant transaction). Existing
 * events only. Opens: a WhatsApp hand-off. Closes: the arrival, cancellation or move of an appointment, a booking request decided,
 * a visit completed. Self check-in failures open their item in the same transaction as the attempt (SelfCheckInService), and the
 * time-based kinds come from DeskExceptionSweepJob, which also closes anything these events missed.
 * comms.handoff.requested.v1 is produced by the WhatsApp hand-off story (ACC-040); the shape read here is {conversationId}, ids only.
 */
@Component
class DeskExceptionProjector implements EventConsumer {

    static final String HANDOFF_REQUESTED = "comms.handoff.requested.v1";

    private final DeskExceptions desk;

    DeskExceptionProjector(DeskExceptions desk) {
        this.desk = desk;
    }

    @Override
    public String group() {
        return "desk-exceptions";
    }

    @Override
    public Set<String> types() {
        return Set.of("scheduling.appointment.arrived.v1", "scheduling.appointment.cancelled.v1", "scheduling.appointment.rescheduled.v1",
                "scheduling.booking.routed.v1", "visit.completed.v1", HANDOFF_REQUESTED);
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.DB_LEDGER;
    }

    @Override
    public void handle(Event e) {
        switch (e.type()) {
            case HANDOFF_REQUESTED -> {
                String conv = e.data().path("conversationId").asString("");
                desk.open(e.tenantId(), DeskExceptions.HANDOFF, "warn", "conversation", conv.isEmpty() ? e.subject() : UUID.fromString(conv), Map.of());
            }
            case "scheduling.appointment.arrived.v1", "scheduling.appointment.cancelled.v1" ->
                    desk.resolveRef(e.tenantId(), id(e, "appointmentId"), DeskExceptions.CHECKIN_FAILED, DeskExceptions.UNCONFIRMED);
            case "scheduling.appointment.rescheduled.v1" -> {
                String old = e.data().path("previousAppointmentId").asString("");
                if (!old.isEmpty()) desk.resolveRef(e.tenantId(), UUID.fromString(old), DeskExceptions.CHECKIN_FAILED, DeskExceptions.UNCONFIRMED);
            }
            case "scheduling.booking.routed.v1" -> {
                String outcome = e.data().path("outcome").asString("");
                if (outcome.equals("confirmed") || outcome.equals("declined")) desk.resolveRef(e.tenantId(), id(e, "appointmentId"), DeskExceptions.REQUEST_SLA);
            }
            default -> desk.resolveRef(e.tenantId(), id(e, "entryId"), DeskExceptions.CHECKOUT_UNPAID); // visit.completed
        }
    }

    private static UUID id(Event e, String field) {
        String v = e.data().path(field).asString("");
        return v.isEmpty() ? e.subject() : UUID.fromString(v);
    }
}
