package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) round 2, COM-044 T1-BUG-3 fix: a rescheduled then cancelled booking, a desk cancel, no over-booking after a reschedule;
 * the disclosed undo-no-show behaviour is printed, not asserted. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCom044PackageReleaseApiTest extends BookingRequestTestBase {

    private String soldPackage(Clinic c, int sessions) {
        ResponseEntity<Map> pkg = post("/v1/packages", c.staffToken(), Map.of("name", "QaRel " + UUID.randomUUID().toString().substring(0, 6), "packageType", "session",
                "price", 1000.00 * sessions, "taxInclusive", false, "validityDays", 60, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", "Laser", "quantity", sessions, "unitListPrice", 1200.00, "taxRatePercent", 0))));
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String packageId = (String) pkg.getBody().get("id");
        post("/v1/packages/" + packageId + "/activate", c.staffToken(), Map.of());
        ResponseEntity<Map> sold = post("/v1/packages/sell", c.staffToken(), Map.of("patientId", c.patientA().toString(), "packageId", packageId, "paymentMethod", "cash"));
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) sold.getBody().get("id");
    }

    private ResponseEntity<Map> bookRaw(Clinic c, String instance, int slot) {
        return route(c, c.refA(), c.slot(slot), Map.of("packageInstanceId", instance));
    }

    private String book(Clinic c, String instance, int slot) {
        ResponseEntity<Map> r = bookRaw(c, instance, slot);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("appointmentId");
    }

    private Object nextSession(Clinic c) {
        String hash = su().queryForObject("SELECT encode(sha256(decode(phone_digits, 'escape')), 'hex') FROM patients WHERE id = ?", String.class, c.patientA());
        ResponseEntity<Map> r = exchange(c.base() + "/households/" + hash, HttpMethod.GET, authed(c.edgeToken()), Map.class);
        return ((List<Map<String, Object>>) r.getBody().get("packageSessions")).stream().filter(s -> c.refA().equals(s.get("patientRef")))
                .findFirst().map(s -> s.get("nextSession")).orElse("none");
    }

    private String sessionOf(String appointment) {
        return su().queryForList("SELECT status FROM package_redemptions WHERE appointment_id = ?::uuid", String.class, appointment).stream().findFirst().orElse(null);
    }

    private int released(String instance) {
        return su().queryForObject("SELECT count(*) FROM package_instance_events WHERE instance_id = ?::uuid AND event_type = 'session_released'", Integer.class, instance);
    }

    private int held(String instance) {
        return su().queryForObject("SELECT count(*) FROM package_redemptions r JOIN package_instance_items i ON i.id = r.instance_item_id "
                + "WHERE i.instance_id = ?::uuid AND r.status = 'booked'", Integer.class, instance);
    }

    private String reschedule(Clinic c, String appointment, int slot) {
        ResponseEntity<Map> moved = post("/v1/appointments/" + appointment + "/reschedule", c.staffToken(), Map.of("newStartTime", c.slot(slot).toString()));
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        return (String) moved.getBody().get("id");
    }

    // C44-A12: rescheduled, then the NEW appointment is cancelled from WhatsApp: the session is free again, released once
    @Test
    void c44a12_rescheduleThenCancelTheNewAppointmentReleasesTheSession() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 2);
        String first = book(c, instance, 0);
        String second = reschedule(c, first, 4);
        ResponseEntity<Map> cancel = post(c.base() + "/appointments/" + second + "/cancel", c.edgeToken(), Map.of("patientRef", c.refA(), "reasonCode", "schedule_clash"));
        System.out.println("C44-A12 reschedule then cancel new: cancel=" + cancel.getStatusCode() + " oldRow=" + sessionOf(first) + " newRow=" + sessionOf(second)
                + " held=" + held(instance) + " nextSession=" + nextSession(c) + " releasedRows=" + released(instance));
        assertThat(cancel.getStatusCode()).as("%s", cancel.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sessionOf(second)).isEqualTo("cancelled");
        assertThat(held(instance)).isZero();
        assertThat(nextSession(c)).isEqualTo(1);
        assertThat(released(instance)).isEqualTo(1);
    }

    // C44-A13: the desk cancels a WhatsApp package booking (staff API): the session is free again
    @Test
    void c44a13_deskCancelReleasesTheSession() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 2);
        String appt = book(c, instance, 0);
        ResponseEntity<Map> cancel = post("/v1/appointments/" + appt + "/cancel", c.staffToken(), Map.of("reasonCode", "schedule_clash"));
        System.out.println("C44-A13 desk cancel=" + cancel.getStatusCode() + " appointment=" + statusOf(UUID.fromString(appt)) + " redemption=" + sessionOf(appt)
                + " nextSession=" + nextSession(c) + " releasedRows=" + released(instance));
        assertThat(cancel.getStatusCode()).as("%s", cancel.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sessionOf(appt)).isEqualTo("cancelled");
        assertThat(nextSession(c)).isEqualTo(1);
        assertThat(released(instance)).isEqualTo(1);
    }

    // C44-A14: a rescheduled booking holds exactly one session: 2-session package = one more booking, the third is refused
    @Test
    void c44a14_afterARescheduleThePackageCannotBeOverBooked() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 2);
        String first = book(c, instance, 0);
        String moved = reschedule(c, first, 4);
        String movedAgain = reschedule(c, moved, 6);
        int heldAfterMoves = held(instance);
        Object offer = nextSession(c);
        ResponseEntity<Map> second = bookRaw(c, instance, 8);
        ResponseEntity<Map> third = bookRaw(c, instance, 10);
        System.out.println("C44-A14 after two reschedules held=" + heldAfterMoves + " rowOnLatest=" + sessionOf(movedAgain) + " offer=" + offer + " second=" + second.getStatusCode()
                + " third=" + third.getStatusCode() + " " + (third.getBody() == null ? "" : third.getBody().get("code")) + " heldAtEnd=" + held(instance) + " releasedRows=" + released(instance));
        assertThat(heldAfterMoves).isEqualTo(1);
        assertThat(sessionOf(movedAgain)).isEqualTo("booked");
        assertThat(offer).isEqualTo(2);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(third.getBody()).containsEntry("code", "PACKAGE_NOT_BOOKABLE");
        assertThat(held(instance)).isEqualTo(2);
    }

    // C44-A15 (record only, disclosed shortcut in NoShowService): a no-show that the desk undoes the same day
    @Test
    void c44a15_undoOfANoShow_recorded() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 2);
        String appt = book(c, instance, 0);
        su().update("UPDATE appointments SET start_time = now() - interval '2 hours', end_time = now() - interval '105 minutes' WHERE id = ?::uuid", appt);
        ResponseEntity<Map> noShow = post("/v1/appointments/" + appt + "/no-show", c.staffToken(), Map.of());
        String afterNoShow = sessionOf(appt);
        ResponseEntity<Map> undo = post("/v1/appointments/" + appt + "/no-show/undo", c.staffToken(), Map.of());
        System.out.println("C44-A15 noShow=" + noShow.getStatusCode() + " redemption=" + afterNoShow + " | undo=" + undo.getStatusCode() + " appointment=" + statusOf(UUID.fromString(appt))
                + " redemption=" + sessionOf(appt) + " held=" + held(instance) + " nextSession=" + nextSession(c) + " (disclosed: the undone appointment holds no session)");
        assertThat(noShow.getStatusCode()).as("%s", noShow.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(afterNoShow).isEqualTo("cancelled");
    }
}
