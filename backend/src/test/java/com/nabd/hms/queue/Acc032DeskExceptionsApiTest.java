package com.nabd.hms.queue;

import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-032: the front desk's exceptions inbox: what opens an item, what closes it by itself, resolve / snooze, permissions, tenants. */
class Acc032DeskExceptionsApiTest extends DeskFixtures {

    @Autowired DeskExceptionSweepJob sweep;
    @Autowired DeskExceptionProjector projector;

    private ResponseEntity<Map> act(String token, Object id, String action, Map<String, Object> body) {
        return exchange("/v1/front-desk/exceptions/" + id + "/" + action, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private void deliver(Clinic c, String type, UUID subject, Map<String, Object> data) {
        var node = objectMapper.valueToTree(data);
        inTenantTx(c.id(), () -> projector.handle(new EventConsumer.Event("ev:in:x", "1-0", UUID.randomUUID().toString(), type, c.id(), subject, 1, node)));
    }

    // ---- unconfirmed bookings (criterion 2 and 3) ----

    @Test
    @SuppressWarnings("unchecked")
    void aBookingUnconfirmedTwoHoursBeforeShowsAsUnconfirmedAndGoesWhenThePatientConfirms() throws Exception {
        Clinic c = clinic("acc032-unconf");
        String phone = phone("acc032-unconf", 1);
        String patient = registerPatient(c, "Asha Rao", phone);
        Instant now = Instant.now();
        UUID soon = insertAppointment(c, patient, now.plus(90, ChronoUnit.MINUTES), now.minus(1, ChronoUnit.DAYS));
        UUID later = insertAppointment(c, registerPatient(c, "Bina Rao", phone), now.plus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.DAYS));
        UUID bookedJustNow = insertAppointment(c, registerPatient(c, "Cara Rao", phone), now.plus(105, ChronoUnit.MINUTES), now);

        sweep.runOnce();
        List<Map<String, Object>> items = itemsFor(c, soon);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("kind", "unconfirmed_booking").containsEntry("refType", "appointment");
        assertThat((List<String>) items.get(0).get("actions")).contains("call", "message");
        assertThat(itemsFor(c, later)).as("more than 2 h away").isEmpty();
        assertThat(itemsFor(c, bookedJustNow)).as("booked inside the window: the booking itself was the confirmation").isEmpty();
        sweep.runOnce();
        assertThat(itemsFor(c, soon)).as("a second sweep adds no duplicate").hasSize(1);

        // the patient confirms in chat (the Edge's confirm call): the item is gone at once, no sweep needed
        String soonRef = refOfAppointment(c, soon);
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = exchange(c.base() + "/appointments/" + soon + "/confirm", HttpMethod.POST, authedJsonBody(c.edgeToken(), Map.of("patientRef", soonRef)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(itemsFor(c, soon)).isEmpty();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("ms").isLessThan(2000);
        assertThat(su().queryForObject("SELECT confirmed_at IS NOT NULL FROM appointments WHERE id = ?", Boolean.class, soon)).isTrue();
        sweep.runOnce();
        assertThat(itemsFor(c, soon)).as("never reopened").isEmpty();
    }

    /** The Edge's reference of the patient behind an appointment (read through the household of the appointment's patient phone). */
    private String refOfAppointment(Clinic c, UUID appointmentId) {
        String patientId = su().queryForObject("SELECT patient_id::text FROM appointments WHERE id = ?", String.class, appointmentId);
        return inTenantTx(c.id(), () -> patientRefsBean.refFor(UUID.fromString(patientId)));
    }

    @Autowired com.nabd.hms.patient.api.PatientRefs patientRefsBean;

    // ---- requests near their SLA, and checkout waiting ----

    @Test
    void aRequestNearItsSlaOpensAnItemAndDecidingItClosesIt() throws Exception {
        Clinic c = clinic("acc032-sla");
        String patient = registerPatient(c, "Asha Rao", phone("acc032-sla", 1));
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID fresh = insertAppointment(c, patient, start, Instant.now());
        UUID near = insertAppointment(c, registerPatient(c, "Bina Rao", phone("acc032-sla", 2)), start.plus(1, ChronoUnit.HOURS), Instant.now());
        inTenantTx(c.id(), () -> {
            jdbc.update("UPDATE appointments SET status = 'requested' WHERE id IN (?, ?)", fresh, near);
            jdbc.update("INSERT INTO booking_request_details (appointment_id, tenant_id, review_reason, sla_due_at) VALUES (?, ?, 'new_patient', now() + interval '10 minutes')", fresh, c.id());
            jdbc.update("INSERT INTO booking_request_details (appointment_id, tenant_id, review_reason, sla_due_at) VALUES (?, ?, 'new_patient', now() + interval '90 seconds')", near, c.id());
        });
        sweep.runOnce();
        assertThat(itemsFor(c, fresh)).as("10 minutes left: not yet").isEmpty();
        List<Map<String, Object>> items = itemsFor(c, near);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("kind", "request_sla").containsEntry("severity", "urgent");

        su().update("UPDATE appointments SET status = 'scheduled' WHERE id = ?", near); // a reviewer accepted it
        deliver(c, "scheduling.booking.routed.v1", near, Map.of("appointmentId", near.toString(), "outcome", "confirmed"));
        assertThat(itemsFor(c, near)).as("closed by the routed event").isEmpty();
    }

    @Test
    void aVisitSittingInCheckoutPendingTooLongOpensAnItemAndCompletingItClosesIt() throws Exception {
        Clinic c = clinic("acc032-checkout");
        String patient = registerPatient(c, "Asha Rao", phone("acc032-checkout", 1));
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO departments (tenant_id, name, is_default) VALUES (?, 'General', true) ON CONFLICT DO NOTHING", c.id()));
        UUID entry = inTenantTx(c.id(), () -> jdbc.queryForObject("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status, source) "
                + "VALUES (?, ?::uuid, ?, (SELECT id FROM departments WHERE tenant_id = ? LIMIT 1), ?, 1, 'checkout_pending', 'walk_in') RETURNING id",
                UUID.class, c.id(), patient, c.doctor().id(), c.id(), java.sql.Date.valueOf(clinicClock.today(c.id()))));
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO queue_entry_events (tenant_id, queue_entry_id, encounter_id, doctor_id, clinic_date, from_status, to_status, kind, occurred_at, source) "
                + "SELECT tenant_id, id, coalesce(encounter_id, gen_random_uuid()), doctor_id, queue_date, 'in_consult', 'checkout_pending', 'transition', now() - interval '10 minutes', 'desk' FROM queue_entries WHERE id = ?", entry));
        sweep.runOnce();
        assertThat(itemsFor(c, entry)).as("10 minutes is under the 30 minute default").isEmpty();
        su().update("UPDATE queue_entry_events SET occurred_at = now() - interval '45 minutes' WHERE queue_entry_id = ?", entry);
        sweep.runOnce();
        List<Map<String, Object>> items = itemsFor(c, entry);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("kind", "checkout_unpaid").containsEntry("refType", "queue_entry");

        deliver(c, "visit.completed.v1", UUID.randomUUID(), Map.of("entryId", entry.toString(), "status", "completed"));
        assertThat(itemsFor(c, entry)).isEmpty();
    }

    // ---- the events: hand-off opens, arrival and cancellation close, replays are harmless ----

    @Test
    @SuppressWarnings("unchecked")
    void aHandoffOpensOnceAndTheArrivalOrCancellationOfAnAppointmentClosesItsItems() throws Exception {
        Clinic c = clinic("acc032-events");
        UUID conv = UUID.randomUUID();
        deliver(c, "comms.handoff.requested.v1", conv, Map.of("conversationId", conv.toString()));
        deliver(c, "comms.handoff.requested.v1", conv, Map.of("conversationId", conv.toString()));
        List<Map<String, Object>> hand = itemsFor(c, conv);
        assertThat(hand).as("a redelivery adds nothing").hasSize(1);
        assertThat(hand.get(0)).containsEntry("kind", "handoff").containsEntry("refType", "conversation");
        assertThat((List<String>) hand.get(0).get("actions")).contains("open_inbox");

        String patient = registerPatient(c, "Asha Rao", phone("acc032-events", 1));
        UUID appt = appointmentToday(c, patient, 0);
        UUID cancelled = appointmentToday(c, registerPatient(c, "Bina Rao", phone("acc032-events", 2)), 30);
        UUID moved = appointmentToday(c, registerPatient(c, "Cara Rao", phone("acc032-events", 3)), 60);
        for (UUID a : List.of(appt, cancelled, moved)) {
            inTenantTx(c.id(), () -> jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id) VALUES (?, 'checkin_failed', 'warn', 'appointment', ?)", c.id(), a));
        }
        deliver(c, "scheduling.appointment.arrived.v1", appt, Map.of("appointmentId", appt.toString()));
        deliver(c, "scheduling.appointment.cancelled.v1", cancelled, Map.of("appointmentId", cancelled.toString()));
        deliver(c, "scheduling.appointment.rescheduled.v1", UUID.randomUUID(), Map.of("appointmentId", UUID.randomUUID().toString(), "previousAppointmentId", moved.toString()));
        assertThat(exceptions(c.staffToken(), "?kind=checkin_failed")).isEmpty();
        assertThat(itemsFor(c, conv)).as("the hand-off stays until a person handles it").hasSize(1);
    }

    @Test
    void theSweepClosesWhatTheEventsMissed() throws Exception {
        Clinic c = clinic("acc032-backstop");
        UUID appt = appointmentToday(c, registerPatient(c, "Asha Rao", phone("acc032-backstop", 1)), 0);
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id) VALUES (?, 'checkin_failed', 'warn', 'appointment', ?)", c.id(), appt));
        assertThat(itemsFor(c, appt)).hasSize(1);
        su().update("UPDATE appointments SET status = 'cancelled', cancel_reason = 'test' WHERE id = ?", appt);
        sweep.runOnce();
        assertThat(itemsFor(c, appt)).isEmpty();
    }

    // ---- resolve, snooze, errors, permissions ----

    @Test
    @SuppressWarnings("unchecked")
    void resolveAndSnoozeWorkAreAuditedAndASecondResolveIsAlreadyResolved() throws Exception {
        Clinic c = clinic("acc032-act");
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID();
        for (UUID a : List.of(a1, a2)) {
            inTenantTx(c.id(), () -> jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id, summary_params) VALUES (?, 'handoff', 'warn', 'conversation', ?, '{}')", c.id(), a));
        }
        String id1 = (String) itemsFor(c, a1).get(0).get("id"), id2 = (String) itemsFor(c, a2).get(0).get("id");

        ResponseEntity<Map> done = act(c.staffToken(), id1, "resolve", Map.of("note", "called the patient"));
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).containsEntry("id", id1).containsEntry("status", "resolved");
        assertThat(itemsFor(c, a1)).isEmpty();
        ResponseEntity<Map> again = act(c.staffToken(), id1, "resolve", Map.of());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "ALREADY_RESOLVED");
        assertThat(su().queryForObject("SELECT resolution || ':' || (resolved_by IS NOT NULL) FROM desk_exceptions WHERE id = ?::uuid", String.class, id1)).isEqualTo("manual:true");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'desk_exception.resolve' AND entity_id = ?::uuid", Integer.class, c.id(), id1)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM desk_exceptions WHERE id = ?::uuid AND summary_params::text LIKE '%called%'", Integer.class, id1)).as("the note is not stored").isZero();

        ResponseEntity<Map> snoozed = act(c.staffToken(), id2, "snooze", Map.of("snoozeMinutes", 60));
        assertThat(snoozed.getBody()).containsEntry("status", "snoozed");
        assertThat(itemsFor(c, a2)).as("hidden while snoozed").isEmpty();
        su().update("UPDATE desk_exceptions SET snoozed_until = now() - interval '1 second' WHERE id = ?::uuid", id2);
        assertThat(itemsFor(c, a2)).as("back when the snooze ends").hasSize(1);
        assertThat(act(c.staffToken(), id2, "snooze", Map.of("snoozeMinutes", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(act(c.staffToken(), id2, "snooze", Map.of("snoozeMinutes", 481)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(act(c.staffToken(), id2, "delete", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(act(c.staffToken(), UUID.randomUUID(), "resolve", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewingNeedsQueueViewAndActingNeedsQueueEdit() throws Exception {
        Clinic c = clinic("acc032-perm");
        UUID ref = UUID.randomUUID();
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id) VALUES (?, 'handoff', 'warn', 'conversation', ?)", c.id(), ref));
        String id = (String) itemsFor(c, ref).get(0).get("id");
        assertThat(exceptions(c.viewerToken(), "")).hasSize(1);
        assertThat(act(c.viewerToken(), id, "resolve", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/front-desk/exceptions", HttpMethod.GET, authed("not.a.jwt"), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/front-desk/exceptions?kind=nope", HttpMethod.GET, authed(c.staffToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(itemsFor(c, ref)).as("still open").hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theInboxIsPagedOldestFirstAndFilteredByKind() throws Exception {
        Clinic c = clinic("acc032-page");
        List<UUID> refs = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID r = UUID.randomUUID();
            refs.add(r);
            inTenantTx(c.id(), () -> jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id, created_at) VALUES (?, 'handoff', 'info', 'conversation', ?, now() - make_interval(mins => ?))",
                    c.id(), r, 10 - refs.size()));
        }
        UUID other = UUID.randomUUID();
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id) VALUES (?, 'checkin_failed', 'warn', 'checkin_attempt', ?)", c.id(), other));
        ResponseEntity<Map> p1 = exchange("/v1/front-desk/exceptions?kind=handoff&limit=2", HttpMethod.GET, authed(c.staffToken()), Map.class);
        List<Map<String, Object>> first = (List<Map<String, Object>>) p1.getBody().get("items");
        assertThat(first).extracting(m -> m.get("refId")).containsExactly(refs.get(0).toString(), refs.get(1).toString());
        String cursor = (String) p1.getBody().get("nextCursor");
        assertThat(cursor).isNotNull();
        ResponseEntity<Map> p2 = exchange("/v1/front-desk/exceptions?kind=handoff&limit=2&cursor=" + cursor, HttpMethod.GET, authed(c.staffToken()), Map.class);
        assertThat((List<Map<String, Object>>) p2.getBody().get("items")).extracting(m -> m.get("refId")).containsExactly(refs.get(2).toString());
        assertThat(p2.getBody().get("nextCursor")).isNull();
        assertThat(exceptions(c.staffToken(), "?kind=checkin_failed")).hasSize(1);
    }

    @Test
    void anItemHoldsIdsAndCodesOnlyAndOneClinicNeverSeesAnothers() throws Exception {
        Clinic a = clinic("acc032-iso-a"), b = clinic("acc032-iso-b");
        UUID conv = UUID.randomUUID();
        deliver(a, "comms.handoff.requested.v1", conv, Map.of("conversationId", conv.toString(), "text", "I am in pain, please call"));
        assertThat(su().queryForObject("SELECT summary_params::text FROM desk_exceptions WHERE ref_id = ?", String.class, conv)).isEqualTo("{}");
        assertThat(exceptions(b.staffToken(), "")).isEmpty();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM desk_exceptions", Integer.class))).isZero();
        // the same ref may be open in two clinics at once (unique per tenant)
        deliver(b, "comms.handoff.requested.v1", conv, Map.of("conversationId", conv.toString()));
        assertThat(itemsFor(a, conv)).hasSize(1);
        assertThat(itemsFor(b, conv)).hasSize(1);
    }
}
