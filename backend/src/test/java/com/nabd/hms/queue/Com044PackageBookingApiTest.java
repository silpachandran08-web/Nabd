package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-044: 'Book session N of M' from the Edge: the household read offers the session and its eligible doctors, the booking links the
 * appointment to the package (a booked session, no entitlement used) and refuses a doctor the package is not restricted to. */
class Com044PackageBookingApiTest extends BookingRequestTestBase {

    /** A package of 3 sessions restricted to the clinic's doctor, sold to patient A. */
    private String soldPackage(Clinic c) {
        ResponseEntity<Map> pkg = post("/v1/packages", c.staffToken(), Map.of("name", "Laser 3", "packageType", "session", "price", 3000.00, "taxInclusive", false,
                "validityDays", 60, "validityStarts", "purchase_date", "graceDays", 7, "eligibleDoctorIds", List.of(c.doctorId().toString()),
                "items", List.of(Map.of("itemType", "service_session", "name", "Laser", "quantity", 3, "unitListPrice", 1200.00, "taxRatePercent", 0))));
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String packageId = (String) pkg.getBody().get("id");
        post("/v1/packages/" + packageId + "/activate", c.staffToken(), Map.of());
        ResponseEntity<Map> sold = post("/v1/packages/sell", c.staffToken(), Map.of("patientId", c.patientA().toString(), "packageId", packageId, "paymentMethod", "cash"));
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) sold.getBody().get("id");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sessions(Clinic c) {
        String hash = su().queryForObject("SELECT encode(sha256(decode(phone_digits, 'escape')), 'hex') FROM patients WHERE id = ?", String.class, c.patientA());
        ResponseEntity<Map> r = exchange(c.base() + "/households/" + hash, HttpMethod.GET, authed(c.edgeToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody().get("packageSessions")).stream().filter(s -> c.refA().equals(s.get("patientRef"))).toList();
    }

    @Test
    void bookSessionOneOfThreeLinksTheAppointmentAndOffersOnlyEligibleDoctors() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c);
        assertThat(sessions(c)).singleElement().satisfies(s -> assertThat(s).containsEntry("instanceId", instance).containsEntry("nextSession", 1)
                .containsEntry("totalSessions", 3).containsEntry("eligibleDoctorIds", List.of(c.doctorId().toString())).doesNotContainKey("packageName"));

        // a doctor the package is not restricted to is refused, nothing is booked
        UUID role = su().queryForObject("SELECT role_id FROM staff WHERE id = ?", UUID.class, c.doctorId());
        UUID otherDoctor = seedStaff(c.tenant(), role, "d2" + UUID.randomUUID() + "@a.com", phone(), false).id();
        ResponseEntity<Map> wrong = route(c, c.refA(), c.slot(0), Map.of("doctorId", otherDoctor.toString(), "packageInstanceId", instance));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody()).containsEntry("code", "DOCTOR_NOT_ELIGIBLE");

        ResponseEntity<Map> ok = route(c, c.refA(), c.slot(1), Map.of("packageInstanceId", instance));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appointment = UUID.fromString((String) ok.getBody().get("appointmentId"));
        Map<String, Object> red = su().queryForMap("SELECT r.status, r.booked_by, i.instance_id FROM package_redemptions r JOIN package_instance_items i ON i.id = r.instance_item_id "
                + "WHERE r.appointment_id = ?", appointment);
        assertThat(red).containsEntry("status", "booked").containsEntry("instance_id", UUID.fromString(instance));
        assertThat(red.get("booked_by")).isNull();
        assertThat(su().queryForObject("SELECT sum(quantity_consumed) FROM package_instance_items WHERE instance_id = ?::uuid", Long.class, instance))
                .as("booking uses no entitlement (NB-154)").isZero();
        assertThat(su().queryForObject("SELECT note FROM package_instance_events WHERE instance_id = ?::uuid AND event_type = 'session_booked'", String.class, instance))
                .startsWith("Session 1 of 3");
        assertThat(sessions(c)).singleElement().satisfies(s -> assertThat(s).containsEntry("nextSession", 2));

        // patient B cannot book on A's package
        ResponseEntity<Map> notTheirs = route(c, c.refB(), c.slot(2), Map.of("packageInstanceId", instance));
        assertThat(notTheirs.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notTheirs.getBody()).containsEntry("code", "PACKAGE_NOT_BOOKABLE");
    }
}
