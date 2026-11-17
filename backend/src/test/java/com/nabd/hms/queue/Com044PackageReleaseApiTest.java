package com.nabd.hms.queue;

import com.nabd.hms.shared.booking.PackageSessionPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-044 (S24 T1-BUG-3): a 'Book session N of M' booking that ends without a visit (cancelled, declined, no-show) gives its session
 * back, a rescheduled one keeps it on the new appointment, and a redeemed session is never given back. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Com044PackageReleaseApiTest extends BookingRequestTestBase {

    @Autowired PackageSessionPort packageSessions;

    private String soldPackage(Clinic c, int sessions) {
        ResponseEntity<Map> pkg = post("/v1/packages", c.staffToken(), Map.of("name", "Laser " + UUID.randomUUID().toString().substring(0, 6), "packageType", "session",
                "price", 1000.00 * sessions, "taxInclusive", false, "validityDays", 60, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", "Laser", "quantity", sessions, "unitListPrice", 1200.00, "taxRatePercent", 0))));
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String packageId = (String) pkg.getBody().get("id");
        post("/v1/packages/" + packageId + "/activate", c.staffToken(), Map.of());
        ResponseEntity<Map> sold = post("/v1/packages/sell", c.staffToken(), Map.of("patientId", c.patientA().toString(), "packageId", packageId, "paymentMethod", "cash"));
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) sold.getBody().get("id");
    }

    /** Books the next session from WhatsApp on the n-th slot; the appointment id. */
    private String book(Clinic c, String instance, int slot) {
        ResponseEntity<Map> r = route(c, c.refA(), c.slot(slot), Map.of("packageInstanceId", instance));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("appointmentId");
    }

    /** The 'Book session N of M' offer of patient A: N. */
    private Object nextSession(Clinic c) {
        String hash = su().queryForObject("SELECT encode(sha256(decode(phone_digits, 'escape')), 'hex') FROM patients WHERE id = ?", String.class, c.patientA());
        ResponseEntity<Map> r = exchange(c.base() + "/households/" + hash, HttpMethod.GET, authed(c.edgeToken()), Map.class);
        return ((List<Map<String, Object>>) r.getBody().get("packageSessions")).stream().filter(s -> c.refA().equals(s.get("patientRef"))).findFirst().orElseThrow().get("nextSession");
    }

    /** Status of the session linked to the appointment, null when none is. */
    private String sessionOf(String appointment) {
        return su().queryForList("SELECT status FROM package_redemptions WHERE appointment_id = ?::uuid", String.class, appointment).stream().findFirst().orElse(null);
    }

    private int released(String instance) {
        return su().queryForObject("SELECT count(*) FROM package_instance_events WHERE instance_id = ?::uuid AND event_type = 'session_released'", Integer.class, instance);
    }

    private long consumed(String instance) {
        return su().queryForObject("SELECT sum(quantity_consumed) FROM package_instance_items WHERE instance_id = ?::uuid", Long.class, instance);
    }

    private ResponseEntity<Map> cancelFromWhatsApp(Clinic c, String appointment) {
        return post(c.base() + "/appointments/" + appointment + "/cancel", c.edgeToken(), Map.of("patientRef", c.refA(), "reasonCode", "schedule_clash"));
    }

    @Test
    void aCancelledBookingGivesTheSessionBackOnceHoweverOftenItIsCancelled() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 2);
        String appointment = book(c, instance, 0);
        assertThat(nextSession(c)).isEqualTo(2);

        assertThat(cancelFromWhatsApp(c, appointment).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(UUID.fromString(appointment))).isEqualTo("cancelled");
        assertThat(sessionOf(appointment)).isEqualTo("cancelled");
        assertThat(nextSession(c)).isEqualTo(1);
        assertThat(released(instance)).isEqualTo(1);

        // cancelled twice: the second tap is refused, and a repeated release finds nothing to give back
        assertThat(cancelFromWhatsApp(c, appointment).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        inTenantTx(c.tenant().id(), () -> packageSessions.releaseAppointment(UUID.fromString(appointment)));
        assertThat(sessionOf(appointment)).isEqualTo("cancelled");
        assertThat(released(instance)).isEqualTo(1);
        assertThat(nextSession(c)).isEqualTo(1);
        assertThat(consumed(instance)).as("nothing was ever used").isZero();

        // both sessions of the package can be booked again, and no third one
        book(c, instance, 2);
        book(c, instance, 4);
        ResponseEntity<Map> third = route(c, c.refA(), c.slot(6), Map.of("packageInstanceId", instance));
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(third.getBody()).containsEntry("code", "PACKAGE_NOT_BOOKABLE");
    }

    @Test
    void aRedeemedSessionIsNotGivenBackWhenItsAppointmentIsCancelled() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 2);
        String appointment = book(c, instance, 0);
        String item = (String) ((List<Map<String, Object>>) exchange("/v1/packages/instances/" + instance, HttpMethod.GET, authed(c.staffToken()), Map.class)
                .getBody().get("items")).get(0).get("id");
        assertThat(post("/v1/packages/instances/items/" + item + "/redeem", c.staffToken(), Map.of()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(sessionOf(appointment)).isEqualTo("redeemed");

        assertThat(post("/v1/appointments/" + appointment + "/cancel", c.staffToken(), Map.of("reasonCode", "schedule_clash")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(UUID.fromString(appointment))).isEqualTo("cancelled");
        assertThat(sessionOf(appointment)).isEqualTo("redeemed");
        assertThat(consumed(instance)).isEqualTo(1);
        assertThat(released(instance)).isZero();
        assertThat(nextSession(c)).as("one used, one left").isEqualTo(2);
    }

    @Test
    void aRescheduledBookingKeepsItsSessionAndANoShowOrADeclinedRequestGivesItBack() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 3);
        String first = book(c, instance, 0);

        // rescheduled: the session follows the appointment, it is neither freed nor counted twice
        ResponseEntity<Map> moved = post("/v1/appointments/" + first + "/reschedule", c.staffToken(), Map.of("newStartTime", c.slot(4).toString()));
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        String second = (String) moved.getBody().get("id");
        assertThat(second).isNotEqualTo(first);
        assertThat(sessionOf(first)).isNull();
        assertThat(sessionOf(second)).isEqualTo("booked");
        assertThat(nextSession(c)).isEqualTo(2);
        assertThat(released(instance)).isZero();

        // no-show: the session is free again
        su().update("UPDATE appointments SET start_time = now() - interval '2 hours', end_time = now() - interval '105 minutes' WHERE id = ?::uuid", second);
        assertThat(post("/v1/appointments/" + second + "/no-show", c.staffToken(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionOf(second)).isEqualTo("cancelled");
        assertThat(nextSession(c)).isEqualTo(1);

        // a request the clinic declines never held a visit: its session is free again
        setPolicy(c, "manual", 40, 10);
        String request = book(c, instance, 8);
        assertThat(statusOf(UUID.fromString(request))).isEqualTo("requested");
        assertThat(nextSession(c)).isEqualTo(2);
        assertThat(post("/v1/booking-requests/" + request + "/decline", c.staffToken(), Map.of("reasonCode", "doctor_unavailable")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionOf(request)).isEqualTo("cancelled");
        assertThat(nextSession(c)).isEqualTo(1);
        assertThat(released(instance)).isEqualTo(2);
        assertThat(consumed(instance)).isZero();
    }
}
