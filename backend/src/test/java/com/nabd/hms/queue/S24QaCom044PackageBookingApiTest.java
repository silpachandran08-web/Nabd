package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) COM-044 'Book session N of M': foreign instances, the last session booked twice, a cancelled booking, a redeemed booking. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCom044PackageBookingApiTest extends BookingRequestTestBase {

    private String soldPackage(Clinic c, int sessions, boolean restricted) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "Q44 " + UUID.randomUUID().toString().substring(0, 6), "packageType", "session",
                "price", 1000.00 * sessions, "taxInclusive", false, "validityDays", 60, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", "Laser", "quantity", sessions, "unitListPrice", 1200.00, "taxRatePercent", 0))));
        if (restricted) body.put("eligibleDoctorIds", List.of(c.doctorId().toString()));
        ResponseEntity<Map> pkg = post("/v1/packages", c.staffToken(), body);
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String packageId = (String) pkg.getBody().get("id");
        post("/v1/packages/" + packageId + "/activate", c.staffToken(), Map.of());
        ResponseEntity<Map> sold = post("/v1/packages/sell", c.staffToken(), Map.of("patientId", c.patientA().toString(), "packageId", packageId, "paymentMethod", "cash"));
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) sold.getBody().get("id");
    }

    private List<Map<String, Object>> sessions(Clinic c) {
        String hash = su().queryForObject("SELECT encode(sha256(decode(phone_digits, 'escape')), 'hex') FROM patients WHERE id = ?", String.class, c.patientA());
        ResponseEntity<Map> r = exchange(c.base() + "/households/" + hash, HttpMethod.GET, authed(c.edgeToken()), Map.class);
        return ((List<Map<String, Object>>) r.getBody().get("packageSessions")).stream().filter(s -> c.refA().equals(s.get("patientRef"))).toList();
    }

    private int booked(String instance) {
        return su().queryForObject("SELECT count(*) FROM package_redemptions r JOIN package_instance_items i ON i.id = r.instance_item_id "
                + "WHERE i.instance_id = ?::uuid AND r.status = 'booked'", Integer.class, instance);
    }

    // C44-A05 + A07: another patient's instance, and another clinic's instance, are never bookable
    @Test
    void c44a05_a07_foreignInstancesAreRefused() {
        Clinic a = clinic();
        setPolicy(a, "auto", 40, 10);
        Clinic b = clinic();
        setPolicy(b, "auto", 40, 10);
        String bInstance = soldPackage(b, 3, false);
        String aInstance = soldPackage(a, 3, false);
        ResponseEntity<Map> otherTenant = route(a, a.refA(), a.slot(0), Map.of("packageInstanceId", bInstance));
        ResponseEntity<Map> otherPatient = route(a, a.refB(), a.slot(1), Map.of("packageInstanceId", aInstance));
        System.out.println("C44-A07 other clinic's instance=" + otherTenant.getStatusCode() + " " + otherTenant.getBody()
                + " | C44-A05 other patient's instance=" + otherPatient.getStatusCode() + " " + otherPatient.getBody());
        assertThat(otherTenant.getStatusCode().value()).isEqualTo(422);
        assertThat(otherPatient.getStatusCode().value()).isEqualTo(422);
        assertThat(booked(bInstance) + booked(aInstance)).isZero();
    }

    // C44-A06: a doctor the package is not restricted to is refused
    @Test
    void c44a06_ineligibleDoctorRefused() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 3, true);
        UUID role = su().queryForObject("SELECT role_id FROM staff WHERE id = ?", UUID.class, c.doctorId());
        UUID other = seedStaff(c.tenant(), role, "q44d" + UUID.randomUUID() + "@a.com", phone(), false).id();
        ResponseEntity<Map> r = route(c, c.refA(), c.slot(0), Map.of("doctorId", other.toString(), "packageInstanceId", instance));
        System.out.println("C44-A06 ineligible doctor=" + r.getStatusCode() + " " + r.getBody());
        assertThat(r.getBody()).containsEntry("code", "DOCTOR_NOT_ELIGIBLE");
        assertThat(booked(instance)).isZero();
    }

    // C44-A08: the last session booked twice (in a row and at once) gives one booking
    @Test
    void c44a08_lastSessionBookedTwiceGivesOneBooking() throws Exception {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String one = soldPackage(c, 1, false);
        ResponseEntity<Map> first = route(c, c.refA(), c.slot(0), Map.of("packageInstanceId", one));
        ResponseEntity<Map> second = route(c, c.refA(), c.slot(2), Map.of("packageInstanceId", one));
        System.out.println("C44-A08 sequential first=" + first.getStatusCode() + " second=" + second.getStatusCode() + " " + second.getBody());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode().value()).isEqualTo(422);

        Clinic d = clinic();
        setPolicy(d, "auto", 40, 10);
        String last = soldPackage(d, 1, false);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int n = i;
            fs.add(pool.submit(() -> {
                go.await();
                return route(d, d.refA(), d.slot(4 + n * 2), Map.of("packageInstanceId", last)).getStatusCode().value();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : fs) codes.add(f.get());
        pool.shutdown();
        System.out.println("C44-A08 concurrent=" + codes + " bookedRedemptions=" + booked(last));
        assertThat(booked(last)).isEqualTo(1);
        assertThat(codes).containsOnlyOnce(201);
    }

    // C44-A09: a WhatsApp booking the patient cancels gives the session back (it must be bookable again)
    @Test
    void c44a09_cancelledBookingFreesTheSession() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 2, false);
        ResponseEntity<Map> ok = route(c, c.refA(), c.slot(0), Map.of("packageInstanceId", instance));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String appt = (String) ok.getBody().get("appointmentId");
        ResponseEntity<Map> cancel = post(c.base() + "/appointments/" + appt + "/cancel", c.edgeToken(), Map.of("patientRef", c.refA(), "reasonCode", "schedule_clash"));
        assertThat(cancel.getStatusCode()).as("%s", cancel.getBody()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> s = sessions(c);
        String redemption = su().queryForObject("SELECT status FROM package_redemptions WHERE appointment_id = ?::uuid", String.class, appt);
        System.out.println("C44-A09 after cancel: appointment=" + statusOf(UUID.fromString(appt)) + " redemption=" + redemption + " household offers=" + s);
        assertThat(s).singleElement().satisfies(x -> assertThat(x).containsEntry("nextSession", 1));
        // and the second session is still bookable twice over the package (2 sessions): book both
        assertThat(route(c, c.refA(), c.slot(3), Map.of("packageInstanceId", instance)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(route(c, c.refA(), c.slot(5), Map.of("packageInstanceId", instance)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // C44-A10: the desk redeems the session the patient booked: it is counted once (next offer is session 2 of 3)
    @Test
    void c44a10_redeemingABookedSessionCountsOnce() {
        Clinic c = clinic();
        setPolicy(c, "auto", 40, 10);
        String instance = soldPackage(c, 3, false);
        assertThat(route(c, c.refA(), c.slot(0), Map.of("packageInstanceId", instance)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String item = (String) ((List<Map<String, Object>>) exchange("/v1/packages/instances/" + instance, HttpMethod.GET, authed(c.staffToken()), Map.class)
                .getBody().get("items")).get(0).get("id");
        ResponseEntity<Map> redeem = post("/v1/packages/instances/items/" + item + "/redeem", c.staffToken(), Map.of());
        List<Map<String, Object>> s = sessions(c);
        System.out.println("C44-A10 redeem=" + redeem.getStatusCode() + " booked=" + booked(instance) + " household offers=" + s);
        assertThat(redeem.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(booked(instance)).isZero();
        assertThat(s).singleElement().satisfies(x -> assertThat(x).containsEntry("nextSession", 2).containsEntry("totalSessions", 3));
    }
}
