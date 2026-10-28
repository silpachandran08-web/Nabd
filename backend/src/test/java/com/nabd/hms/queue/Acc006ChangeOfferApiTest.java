package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-006: staff reschedule/cancel notify the patient; a leave block lists the bookings it covers with the nearest alternatives;
 * one tap offers them; the patient's tap books the new slot and cancels the old one (clinic_initiated); a taken slot answers
 * 409 with the next alternative.
 */
@SuppressWarnings("unchecked")
class Acc006ChangeOfferApiTest extends BookingRequestTestBase {

    private void approvedTemplates(Clinic c) {
        for (String name : List.of("appt_rescheduled", "appt_cancelled_by_clinic")) {
            su().update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, ?, ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, {{2}} {{3}}', 3, 'approved')", c.tenant().id(), name, "m_" + UUID.randomUUID().toString().replace("-", ""));
        }
    }

    private void messagingConsent(Clinic c, UUID patient) {
        su().update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?, ?, 'messaging')", c.tenant().id(), patient);
    }

    private UUID book(Clinic c, UUID patient, Instant at) {
        ResponseEntity<Map> r = post("/v1/appointments", c.staffToken(), Map.of("doctorId", c.doctorId().toString(), "patientId", patient.toString(), "startTime", at.toString()));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private List<Map<String, Object>> requests(Clinic c, String purpose) {
        return su().queryForList("SELECT status, recipient_patient_id FROM notification_requests WHERE tenant_id = ? AND purpose = ? ORDER BY created_at", c.tenant().id(), purpose);
    }

    @Test
    void staffRescheduleAndCancelNotifyThePatientAndTheEventsCarryWhoAndWhy() {
        Clinic c = clinic();
        approvedTemplates(c);
        messagingConsent(c, c.patientA());
        UUID a = book(c, c.patientA(), c.slot(0)), b = book(c, c.patientB(), c.slot(1));

        ResponseEntity<Map> moved = post("/v1/appointments/" + a + "/reschedule", c.staffToken(), Map.of("newStartTime", c.slot(4).toString()));
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requests(c, "appt_rescheduled")).extracting(r -> r.get("status")).containsExactly("requested");

        ResponseEntity<Map> cancelled = post("/v1/appointments/" + b + "/cancel", c.staffToken(), Map.of("reasonCode", "doctor_unavailable"));
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requests(c, "appt_cancelled_by_clinic")).as("no messaging consent: recorded as blocked, nothing sent").extracting(r -> r.get("status")).containsExactly("blocked");

        assertThat(events(c, "scheduling.appointment.rescheduled.v1").get(0)).containsEntry("by", "staff").containsEntry("reasonCode", "rescheduled");
        assertThat(events(c, "scheduling.appointment.cancelled.v1").stream().filter(e -> b.toString().equals(e.get("appointmentId"))).findFirst().orElseThrow())
                .containsEntry("by", "staff").containsEntry("reasonCode", "doctor_unavailable");
    }

    @Test
    void aClinicWithoutAnApprovedTemplateStillMovesTheBooking() {
        Clinic c = clinic();
        messagingConsent(c, c.patientA());
        UUID a = book(c, c.patientA(), c.slot(0));
        assertThat(post("/v1/appointments/" + a + "/reschedule", c.staffToken(), Map.of("newStartTime", c.slot(4).toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requests(c, "appt_rescheduled")).isEmpty();
    }

    @Test
    void leaveListsAffectedBookingsOfferThenAcceptCancelsTheOldOne() {
        Clinic c = clinic();
        messagingConsent(c, c.patientA());
        UUID a = book(c, c.patientA(), c.slot(0)), b = book(c, c.patientB(), c.slot(1));
        String day = c.slot().atZone(c.zone()).toLocalDate().toString();

        ResponseEntity<Map> leave = post("/v1/doctors/" + c.doctorId() + "/leave", c.staffToken(), Map.of("dateFrom", day, "dateTo", day, "startTime", "10:00", "endTime", "10:30"));
        assertThat(leave.getStatusCode()).as(String.valueOf(leave.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) leave.getBody().get("affectedAppointments")).hasSize(2);
        assertThat(su().queryForObject("SELECT needs_reschedule_reason FROM appointments WHERE id = ?", String.class, a)).isEqualTo("doctor_leave");
        UUID leaveId = UUID.fromString((String) leave.getBody().get("id"));

        long t0 = System.nanoTime();
        ResponseEntity<Map> list = exchange("/v1/doctors/" + c.doctorId() + "/leave/" + leaveId + "/affected-appointments", HttpMethod.GET, authed(c.staffToken()), Map.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).as("affected list latency").isLessThan(2000);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.getBody().get("items");
        assertThat(items).hasSize(2);
        Map<String, Object> rowA = items.stream().filter(i -> a.toString().equals(i.get("appointmentId"))).findFirst().orElseThrow();
        Map<String, Object> rowB = items.stream().filter(i -> b.toString().equals(i.get("appointmentId"))).findFirst().orElseThrow();
        assertThat(rowA).containsEntry("channelConsent", true).containsEntry("patientRef", c.refA());
        assertThat(rowB).containsEntry("channelConsent", false);
        List<String> alts = (List<String>) rowA.get("alternatives");
        assertThat(alts).hasSize(3);
        assertThat(alts.stream().map(Instant::parse)).allMatch(i -> !i.isBefore(c.slot(2)) && !i.isBefore(c.slot(0).plusSeconds(1800)))
                .as("none inside the 10:00-10:30 leave window").doesNotContain(c.slot(0), c.slot(1));
        assertThat(exchange("/v1/doctors/" + c.doctorId() + "/leave/" + UUID.randomUUID() + "/affected-appointments", HttpMethod.GET, authed(c.staffToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // no consent: the desk calls the patient instead
        ResponseEntity<Map> refused = post("/v1/appointments/" + b + "/offer-alternatives", c.staffToken(), Map.of());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody()).containsEntry("code", "NO_MESSAGING_CONSENT");

        ResponseEntity<Map> first = post("/v1/appointments/" + a + "/offer-alternatives", c.staffToken(), Map.of());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> second = post("/v1/appointments/" + a + "/offer-alternatives", c.staffToken(), Map.of());
        UUID offer = UUID.fromString((String) second.getBody().get("offerId"));
        assertThat(su().queryForObject("SELECT status FROM appointment_change_offers WHERE id = ?::uuid", String.class, first.getBody().get("offerId"))).isEqualTo("superseded");
        Map<String, Object> ev = events(c, "scheduling.booking.routed.v1").get(1);
        assertThat(ev).containsEntry("outcome", "suggested").containsEntry("offerKind", "change").containsEntry("offerId", offer.toString());
        List<String> slots = (List<String>) second.getBody().get("slots");

        String accept = c.base() + "/change-offers/" + offer + "/accept";
        assertThat(post(accept, c.edgeToken(), Map.of("slotStart", c.slot(0).toString())).getStatusCode()).as("not an offered slot").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> tap = post(accept, c.edgeToken(), Map.of("slotStart", slots.get(0)));
        assertThat(tap.getStatusCode()).as(String.valueOf(tap.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(tap.getBody()).containsEntry("status", "CONFIRMED");
        UUID newId = UUID.fromString((String) tap.getBody().get("newAppointmentId"));
        assertThat(statusOf(newId)).isEqualTo("scheduled");
        assertThat(su().queryForMap("SELECT status, cancel_reason FROM appointments WHERE id = ?", a)).containsEntry("status", "cancelled").containsEntry("cancel_reason", "clinic_initiated");
        assertThat(post(accept, c.edgeToken(), Map.of("slotStart", slots.get(0))).getBody()).as("a repeated tap is the same answer").containsEntry("newAppointmentId", newId.toString());
        assertThat(post(c.base() + "/change-offers/" + UUID.fromString((String) first.getBody().get("offerId")) + "/accept", c.edgeToken(), Map.of("slotStart", slots.get(0))).getStatusCode())
                .as("superseded").isEqualTo(HttpStatus.GONE);
    }

    @Test
    void aSlotTakenMeanwhileGives409WithTheNextAlternativeAndAnExpiredOfferIs410() {
        Clinic c = clinic();
        messagingConsent(c, c.patientA());
        UUID a = book(c, c.patientA(), c.slot(0));
        ResponseEntity<Map> o = post("/v1/appointments/" + a + "/offer-alternatives", c.staffToken(), Map.of("slots", List.of(c.slot(4).toString(), c.slot(5).toString())));
        assertThat(o.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID offer = UUID.fromString((String) o.getBody().get("offerId"));
        book(c, c.patientB(), c.slot(4)); // the desk fills the first alternative

        String accept = c.base() + "/change-offers/" + offer + "/accept";
        ResponseEntity<Map> taken = post(accept, c.edgeToken(), Map.of("slotStart", c.slot(4).toString()));
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody()).containsEntry("code", "SLOT_TAKEN").containsKey("nextAlternative");
        assertThat(statusOf(a)).as("nothing changed").isEqualTo("scheduled");
        assertThat(post(accept, c.edgeToken(), Map.of("slotStart", c.slot(5).toString())).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID b2 = book(c, c.patientA(), c.slot(8));
        UUID lapsed = UUID.fromString((String) post("/v1/appointments/" + b2 + "/offer-alternatives", c.staffToken(), Map.of("slots", List.of(c.slot(9).toString()))).getBody().get("offerId"));
        su().update("UPDATE appointment_change_offers SET expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(60)), lapsed);
        assertThat(post(c.base() + "/change-offers/" + lapsed + "/accept", c.edgeToken(), Map.of("slotStart", c.slot(9).toString())).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void offeringNeedsAScheduledBookingAndEdgeIdentity() {
        Clinic c = clinic();
        messagingConsent(c, c.patientA());
        UUID a = book(c, c.patientA(), c.slot(0));
        assertThat(post("/v1/appointments/" + a + "/cancel", c.staffToken(), Map.of("reasonCode", "illness")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = post("/v1/appointments/" + a + "/offer-alternatives", c.staffToken(), Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "APPOINTMENT_NOT_SCHEDULED");
        assertThat(post(c.base() + "/change-offers/" + UUID.randomUUID() + "/accept", c.staffToken(), Map.of("slotStart", c.slot(2).toString())).getStatusCode())
                .as("a staff token is not the Edge").isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
        assertThat(post(c.base() + "/change-offers/" + UUID.randomUUID() + "/accept", c.edgeToken(), Map.of("slotStart", c.slot(2).toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
