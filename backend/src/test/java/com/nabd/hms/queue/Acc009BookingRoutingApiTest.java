package com.nabd.hms.queue;

import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-009: bookings from a patient channel are confirmed, or held for review, by the clinic's routing policy. */
class Acc009BookingRoutingApiTest extends BookingRequestTestBase {

    @Autowired BookingEscalationJob escalation;

    private static UUID id(ResponseEntity<Map> r) {
        return UUID.fromString((String) r.getBody().get("appointmentId"));
    }

    @Test
    void hybridConfirmsAReturningPatientAndAuditsItWithThePolicyVersion() {
        Clinic c = clinic();
        setPolicy(c, "hybrid", 40, 10);
        giveHistory(c, c.patientA());
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = route(c, c.refA(), c.slot(), Map.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("status", "CONFIRMED");
        assertThat(ms).as("one decision, end to end").isLessThan(2000);
        Map<String, Object> row = su().queryForMap("SELECT status, decision_source, channel, visit_type, policy_version FROM appointments WHERE id = ?", id(r));
        assertThat(row).containsEntry("status", "scheduled").containsEntry("decision_source", "auto").containsEntry("channel", "whatsapp")
                .containsEntry("visit_type", "follow_up").containsEntry("policy_version", 7);
        assertThat(su().queryForObject("SELECT count(*) FROM booking_request_details WHERE appointment_id = ?", Integer.class, id(r))).isZero();
        // the confirmation event exists in the same committed transaction as the row (sent only after commit by the outbox relay)
        assertThat(events(c, "scheduling.booking.routed.v1")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("outcome", "confirmed").containsEntry("decisionSource", "auto").doesNotContainKey("patientRef"));
        assertThat(events(c, "scheduling.appointment.booked.v1")).hasSize(1);
        assertThat(su().queryForObject("SELECT after->>'patientRef' FROM audit_log WHERE tenant_id = ? AND action = 'booking.auto_confirmed' AND entity_id = ?",
                String.class, c.tenant().id(), id(r))).isEqualTo(c.refA());
    }

    @Test
    void hybridHoldsANewPatientAndNobodyElseCanTakeTheSlot() {
        Clinic c = clinic();
        ResponseEntity<Map> r = route(c, c.refA(), c.slot(), Map.of("note", "first visit please"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("status", "REQUESTED").containsKey("slaDueAt");
        assertThat(statusOf(id(r))).isEqualTo("requested");
        Map<String, Object> d = su().queryForMap("SELECT review_reason, sla_due_at, patient_note FROM booking_request_details WHERE appointment_id = ?", id(r));
        assertThat(d).containsEntry("review_reason", "new_patient").containsEntry("patient_note", "first visit please");
        Instant sla = ((java.sql.Timestamp) d.get("sla_due_at")).toInstant();
        assertThat(java.time.Duration.between(Instant.now(), sla).toMinutes()).as("default SLA is 10 minutes").isBetween(8L, 10L);

        // held: another patient (channel or desk) gets 409 SLOT_TAKEN
        ResponseEntity<Map> other = route(c, c.refB(), c.slot(), Map.of());
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getBody()).containsEntry("code", "SLOT_TAKEN");
        ResponseEntity<Map> desk = post("/v1/appointments", c.staffToken(), Map.of("patientId", c.patientB().toString(), "doctorId", c.doctorId().toString(), "startTime", c.slot().toString()));
        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // the availability read no longer offers the held slot
        String day = c.slot().atZone(c.zone()).toLocalDate().toString();
        assertThat(exchange(c.base() + "/availability?doctorId=" + c.doctorId() + "&from=" + day + "&to=" + day, HttpMethod.GET, authed(c.edgeToken()), String.class).getBody())
                .doesNotContain(c.slot().toString());
        assertThat(events(c, "scheduling.booking.routed.v1")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("outcome", "requested").containsEntry("reviewReason", "new_patient").containsKey("slaDueAt")
                        .doesNotContainKeys("note", "patientNote", "patientRef"));
    }

    @Test
    void autoModeHoldsTheBookingOverTheDailyCapWithReasonOverCap() {
        Clinic c = clinic();
        setPolicy(c, "auto", 3, 10);
        List<String> statuses = new ArrayList<>();
        UUID last = null;
        for (int i = 0; i < 4; i++) {
            ResponseEntity<Map> r = route(c, c.refA(), c.slot(i), Map.of());
            statuses.add((String) r.getBody().get("status"));
            last = id(r);
        }
        assertThat(statuses).containsExactly("CONFIRMED", "CONFIRMED", "CONFIRMED", "REQUESTED");
        assertThat(su().queryForObject("SELECT review_reason FROM booking_request_details WHERE appointment_id = ?", String.class, last)).isEqualTo("over_cap");
    }

    @Test
    void theCapHoldsUnderConcurrentBookings() {
        Clinic c = clinic();
        setPolicy(c, "auto", 5, 10);
        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int n = i;
            tasks.add(() -> route(c, c.refA(), c.slot(n), Map.of()));
        }
        List<String> statuses = ConcurrencyHarness.race(tasks).stream().map(r -> (String) r.getBody().get("status")).toList();
        assertThat(Collections.frequency(statuses, "CONFIRMED")).as("%s", statuses).isEqualTo(5);
        assertThat(Collections.frequency(statuses, "REQUESTED")).isEqualTo(4);
        assertThat(su().queryForObject("SELECT count(*) FROM appointments WHERE tenant_id = ? AND decision_source = 'auto'", Integer.class, c.tenant().id())).isEqualTo(5);
    }

    @Test
    void aTriageFlagHoldsTheBookingEvenInAutoModeAndSortsFirstInTheInbox() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        ResponseEntity<Map> plain = route(c, c.refA(), c.slot(0), Map.of("group", true)); // auto mode confirms groups
        assertThat(plain.getBody()).containsEntry("status", "CONFIRMED");
        setPolicy(c, "manual", 40, 10);
        ResponseEntity<Map> early = route(c, c.refA(), c.slot(1), Map.of());
        setPolicy(c, "auto", 40, 10);
        ResponseEntity<Map> flagged = route(c, c.refB(), c.slot(2), Map.of("triageFlags", List.of("chest_pain"), "note", "tight chest"));
        assertThat(early.getBody()).containsEntry("status", "REQUESTED");
        assertThat(flagged.getBody()).containsEntry("status", "REQUESTED");
        assertThat(su().queryForObject("SELECT review_reason FROM booking_request_details WHERE appointment_id = ?", String.class, id(flagged))).isEqualTo("triage_flag");
        assertThat(su().queryForObject("SELECT triage_flags::text FROM booking_request_details WHERE appointment_id = ?", String.class, id(flagged))).contains("chest_pain");

        ResponseEntity<Map> inbox = exchange("/v1/booking-requests", HttpMethod.GET, authed(c.staffToken()), Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) inbox.getBody().get("items");
        assertThat(items).extracting(i -> i.get("appointmentId")).containsExactly(id(flagged).toString(), id(early).toString());
        assertThat(inbox.getBody().toString()).as("the patient's note is never returned").doesNotContain("tight chest");
    }

    @Test
    void anOverdueRequestIsEscalatedOnceAndNeverAutoDeclined() {
        Clinic c = clinic();
        ResponseEntity<Map> r = route(c, c.refA(), c.slot(), Map.of());
        UUID id = id(r);
        assertThat(escalation.runOnce()).as("not due yet").isZero();
        su().update("UPDATE booking_request_details SET sla_due_at = now() - interval '1 minute' WHERE appointment_id = ?", id);
        assertThat(escalation.runOnce()).isEqualTo(1);
        assertThat(escalation.runOnce()).as("once only").isZero();
        assertThat(statusOf(id)).as("never auto-declined").isEqualTo("requested");
        assertThat(su().queryForObject("SELECT escalated_at IS NOT NULL FROM booking_request_details WHERE appointment_id = ?", Boolean.class, id)).isTrue();
        assertThat(events(c, "scheduling.booking.routed.v1")).extracting(e -> e.get("outcome")).containsExactly("requested", "escalated");
        assertThat(events(c, "scheduling.booking.routed.v1").get(1)).containsEntry("reviewerRole", "receptionist").containsEntry("notifyOwner", true);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'booking_request.escalated' AND entity_id = ?", Integer.class, c.tenant().id(), id)).isEqualTo(1);
    }

    @Test
    void aSlaFromThePolicyIsUsed() {
        Clinic c = clinic();
        setPolicy(c, "manual", 40, 30);
        UUID id = id(route(c, c.refA(), c.slot(), Map.of()));
        Instant sla = su().queryForObject("SELECT sla_due_at FROM booking_request_details WHERE appointment_id = ?", java.sql.Timestamp.class, id).toInstant();
        assertThat(java.time.Duration.between(Instant.now(), sla).toMinutes()).isBetween(28L, 30L);
        assertThat(su().queryForObject("SELECT review_reason FROM booking_request_details WHERE appointment_id = ?", String.class, id)).isEqualTo("policy_review");
    }

    @Test
    void validationAndOwnershipRefusals() {
        Clinic c = clinic();
        assertThat(route(c, c.refA(), c.slot(), Map.of("channel", "carrier_pigeon")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(route(c, c.refA(), c.slot(), Map.of("visitType", "walkin")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(route(c, c.refA(), c.slot(), Map.of("note", "x".repeat(281))).getStatusCode()).as("note boundary 281").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(route(c, c.refA(), c.slot(), Map.of("triageFlags", List.of("Chest Pain"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(route(c, c.refA(), c.slot().plusSeconds(60), Map.of()).getStatusCode()).as("off the slot grid").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> unknown = route(c, "no-such-reference", c.slot(), Map.of());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(unknown.getBody()).containsEntry("code", "NOT_OWNER");
        assertThat(route(c, c.refA(), c.slot(), Map.of("note", "x".repeat(280))).getStatusCode()).as("note boundary 280").isEqualTo(HttpStatus.CREATED);
        // a staff token is not a service identity; another clinic's Edge token is refused
        assertThat(post(c.base() + "/booking-requests", c.staffToken(), Map.of("patientRef", c.refA())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Clinic other = clinic();
        assertThat(post(c.base() + "/booking-requests", other.edgeToken(), Map.of("patientRef", c.refA(), "doctorId", c.doctorId().toString(),
                "slotStart", c.slot(3).toString(), "channel", "web")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Idempotency-Key is required and a replay answers the same
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.edgeToken());
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(http.exchange(url(c.base() + "/booking-requests"), HttpMethod.POST, new org.springframework.http.HttpEntity<>("{}", h), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var req = authedJsonBody(c.edgeToken(), Map.of("patientRef", c.refB(), "doctorId", c.doctorId().toString(), "slotStart", c.slot(5).toString(), "channel", "app"));
        ResponseEntity<String> first = http.exchange(url(c.base() + "/booking-requests"), HttpMethod.POST, req, String.class);
        ResponseEntity<String> replay = http.exchange(url(c.base() + "/booking-requests"), HttpMethod.POST, req, String.class);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(su().queryForObject("SELECT count(*) FROM appointments WHERE tenant_id = ? AND start_time = ?", Integer.class, c.tenant().id(), java.sql.Timestamp.from(c.slot(5)))).isEqualTo(1);
    }

    @Test
    void aStaffBookingStaysConfirmedAndSaysSo() {
        Clinic c = clinic();
        setPolicy(c, "manual", 0, 10); // the routing policy never holds the desk's own bookings
        ResponseEntity<Map> r = post("/v1/appointments", c.staffToken(), Map.of("patientId", c.patientA().toString(), "doctorId", c.doctorId().toString(), "startTime", c.slot().toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("status", "scheduled").containsEntry("decisionSource", "staff").containsEntry("channel", "desk");
    }

    @Test
    void fiftyBookingsASecondMinuteStayFarUnderTwoSecondsAtP95() {
        // k6 is not installed in this sandbox: 50 sequential decisions through the real HTTP stack stand in for the load script (perf/k6/acc009-booking-decision.js)
        Clinic c = clinic();
        setPolicy(c, "auto", 1000, 10);
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long t0 = System.nanoTime();
            assertThat(route(c, c.refA(), c.slot(i), Map.of()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(ms);
        assertThat(ms.get((int) Math.ceil(0.95 * ms.size()) - 1)).as("p95 ms of %s", ms).isLessThan(2000);
    }

    @Test
    void requestDetailsAreIsolatedPerTenant() {
        Clinic a = clinic(), b = clinic();
        UUID id = id(route(a, a.refA(), a.slot(), Map.of()));
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM booking_request_details WHERE appointment_id = ?", Integer.class, id))).isEqualTo(1);
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM booking_request_details WHERE appointment_id = ?", Integer.class, id))).isZero();
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM booking_routing_settings", Integer.class))).isZero();
        // and tenant B's staff never sees tenant A's request in the inbox
        ResponseEntity<Map> inbox = exchange("/v1/booking-requests", HttpMethod.GET, authed(b.staffToken()), Map.class);
        assertThat((List<?>) inbox.getBody().get("items")).isEmpty();
    }
}
