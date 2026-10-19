package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S10) for ACC-004, written from the story's acceptance criteria. All calls go through HTTP. */
class S10QaSlotRulesTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(9000);

    private record Clinic(UUID tenantId, String token, UUID doctor, String patient, LocalDate day, ZoneId zone) {
        Instant at(String hhmm) {
            return day.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant();
        }
    }

    /** Doctor works 09:00-13:00 on 15-minute slots on a day three days ahead. */
    private Clinic clinic(String tz) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", tz, tenant.id());
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "s10qa4" + n + "@a.com", "+9186" + (1000000 + n), false);
        String token = loginAndGetAccessToken(doctor);
        ZoneId zone = ZoneId.of(tz);
        LocalDate day = LocalDate.now(zone).plusDays(3);
        hours(token, doctor.id(), day, "09:00:00", "13:00:00", 15);
        return new Clinic(tenant.id(), token, doctor.id(), patient(token), day, zone);
    }

    private void hours(String token, UUID doctor, LocalDate day, String from, String to, int slot) {
        ResponseEntity<Map> r = exchange("/v1/doctors/" + doctor + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", day.getDayOfWeek().getValue() % 7, "startTime", from, "endTime", to, "slotMinutes", slot)), Map.class);
        assertThat(r.getStatusCode()).as("hours " + r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private String patient(String token) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+9185" + (1000000 + n), "dob", (1940 + n % 50) + "-0" + (1 + n % 9) + "-1" + (n % 9), "gender", "female")), Map.class);
        assertThat(p.getStatusCode()).as("patient " + p.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) p.getBody().get("id");
    }

    private ResponseEntity<Map> book(Clinic c, Instant start) {
        return book(c, c.patient, start);
    }

    private ResponseEntity<Map> book(Clinic c, String patient, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "doctorId", c.doctor.toString(), "patientId", patient, "startTime", start.toString())), Map.class);
    }

    private static void assertProblem(ResponseEntity<Map> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(status);
        assertThat(r.getBody().get("code")).isEqualTo(code);
    }

    // ---------- AC1 ----------
    /** Literal story wording: 10:05 on a 15-minute grid with a booking at 10:00 -> 409 SLOT_TAKEN.
     *  AC3 says an off-grid time is 400 OFF_GRID and 10:05 is off the grid, so the two criteria conflict. */
    @Test
    // known defect (ACC-004): AC1 literal 10:05 conflicts with AC3 off-grid 400; story spec unresolved
    @Tag("known-defect-ACC-004")
    void ac1_literal_1005AgainstBookingAt1000_is409SlotTaken() {
        Clinic c = clinic("Asia/Kolkata");
        assertThat(book(c, c.at("10:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(book(c, patient(c.token), c.at("10:05")), HttpStatus.CONFLICT, "SLOT_TAKEN");
    }

    @Test
    void ac1_sameStartIsSlotTaken_backToBackIsFine_andDbConstraintRefusesOverlap() {
        Clinic c = clinic("Asia/Kolkata");
        assertThat(book(c, c.at("10:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(book(c, patient(c.token), c.at("10:00")), HttpStatus.CONFLICT, "SLOT_TAKEN");
        assertThat(book(c, patient(c.token), c.at("10:15")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(book(c, patient(c.token), c.at("09:45")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // the database itself refuses a 10:05-10:20 row that overlaps 10:00-10:15
        assertThatThrownBy(() -> inTenantTx(c.tenantId, () -> jdbc.update(
                "INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), c.tenantId, UUID.fromString(c.patient), c.doctor, Timestamp.from(c.at("10:05")), Timestamp.from(c.at("10:20")))))
                .hasMessageContaining("ex_appointments_doctor_overlap");
    }

    // ---------- AC2 ----------
    @Test
    void ac2_twentyConcurrentSameTime_oneCreated_nineteenSlotTaken() throws Exception {
        Clinic c = clinic("Asia/Dubai");
        List<String> patients = new ArrayList<>();
        for (int i = 0; i < 20; i++) patients.add(patient(c.token));
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch ready = new CountDownLatch(20), go = new CountDownLatch(1);
            List<Callable<ResponseEntity<Map>>> jobs = new ArrayList<>();
            for (String p : patients) jobs.add(() -> { ready.countDown(); go.await(); return book(c, p, c.at("11:00")); });
            List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
            for (Callable<ResponseEntity<Map>> j : jobs) fs.add(pool.submit(j));
            ready.await(); go.countDown();
            int created = 0, taken = 0, other = 0;
            for (Future<ResponseEntity<Map>> f : fs) {
                ResponseEntity<Map> r = f.get(120, java.util.concurrent.TimeUnit.SECONDS);
                if (r.getStatusCode() == HttpStatus.CREATED) created++;
                else if (r.getStatusCode() == HttpStatus.CONFLICT && "SLOT_TAKEN".equals(r.getBody().get("code"))) taken++;
                else other++;
            }
            assertThat(created).isEqualTo(1);
            assertThat(taken).isEqualTo(19);
            assertThat(other).isZero();
        } finally { pool.shutdownNow(); }
    }

    // ---------- AC3 ----------
    @Test
    void ac3_leaveOutsideHoursOffGridPastHoliday_eachNamed() {
        Clinic c = clinic("Asia/Riyadh");
        assertProblem(book(c, c.at("14:00")), HttpStatus.BAD_REQUEST, "OUTSIDE_HOURS");
        assertProblem(book(c, c.at("08:45")), HttpStatus.BAD_REQUEST, "OUTSIDE_HOURS");
        assertProblem(book(c, c.at("10:07")), HttpStatus.BAD_REQUEST, "OFF_GRID");
        assertProblem(book(c, c.day.atTime(10, 0, 30).atZone(c.zone).toInstant()), HttpStatus.BAD_REQUEST, "OFF_GRID"); // seconds
        assertProblem(book(c, c.day.minusDays(7).atTime(10, 0).atZone(c.zone).toInstant()), HttpStatus.BAD_REQUEST, "IN_PAST");
        // boundaries: first slot 09:00 and last slot 12:45 are fine; 13:00 is not
        assertThat(book(c, c.at("09:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(book(c, patient(c.token), c.at("12:45")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(book(c, c.at("13:00")), HttpStatus.BAD_REQUEST, "OUTSIDE_HOURS");
        // a weekday the doctor has no hours at all
        Instant noHours = c.day.plusDays(1).atTime(10, 0).atZone(c.zone).toInstant();
        assertProblem(book(c, noHours), HttpStatus.BAD_REQUEST, "OUTSIDE_HOURS");
        // nothing was written by the refusals
        assertThat(inTenantTx(c.tenantId, () -> jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))).isEqualTo(2);
    }

    @Test
    void ac3_doctorOnLeave_andClinicHoliday() {
        Clinic c = clinic("Asia/Qatar");
        exchange("/v1/doctors/" + c.doctor + "/leave", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "dateFrom", c.day.toString(), "dateTo", c.day.toString(), "reason", "conference")), Map.class);
        assertProblem(book(c, c.at("11:00")), HttpStatus.BAD_REQUEST, "DOCTOR_ON_LEAVE");
        List av = exchange("/v1/doctors/" + c.doctor + "/availability?date=" + c.day, HttpMethod.GET, authed(c.token), List.class).getBody();
        assertThat(av).as("availability hides leave day").isEmpty();

        LocalDate holiday = c.day.plusDays(7);
        exchange("/v1/setup/holidays", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "holidayDate", holiday.toString(), "name", "Closed", "recurring", false)), Map.class);
        assertProblem(book(c, holiday.atTime(10, 0).atZone(c.zone).toInstant()), HttpStatus.BAD_REQUEST, "CLINIC_CLOSED");
    }

    @Test
    void ac3_problemBodyIsNotGeneric409_andMissingFieldsAre400() {
        Clinic c = clinic("Asia/Kolkata");
        book(c, c.at("10:00"));
        ResponseEntity<Map> r = book(c, patient(c.token), c.at("10:00"));
        assertThat(r.getBody()).containsKeys("type", "title", "status", "code");
        assertThat(r.getBody().get("type").toString()).endsWith("/slot-taken");
        assertThat(exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of("doctorId", c.doctor.toString(), "patientId", c.patient)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- AC4 ----------
    @Test
    void ac4_rescheduleAndWaitlistChosenSlot_returnSameErrorsAsBooking() {
        Clinic c = clinic("Asia/Kolkata");
        String id = (String) book(c, c.at("10:00")).getBody().get("id");
        String other = patient(c.token);
        assertThat(book(c, other, c.at("10:15")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        exchange("/v1/doctors/" + c.doctor + "/leave", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "dateFrom", c.day.plusDays(7).toString(), "dateTo", c.day.plusDays(7).toString(), "reason", "x")), Map.class);
        LocalDate hol = c.day.plusDays(14);
        exchange("/v1/setup/holidays", HttpMethod.POST, authedJsonBody(c.token, Map.of("holidayDate", hol.toString(), "name", "Closed", "recurring", false)), Map.class);

        String entry = (String) exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "doctorId", c.doctor.toString(), "patientId", other)), Map.class).getBody().get("id");
        Object[][] cases = {
                {c.at("10:07"), 400, "OFF_GRID"}, {c.at("14:00"), 400, "OUTSIDE_HOURS"},
                {c.day.minusDays(7).atTime(10, 0).atZone(c.zone).toInstant(), 400, "IN_PAST"},
                {c.at("10:15"), 409, "SLOT_TAKEN"},
                {c.day.plusDays(7).atTime(10, 0).atZone(c.zone).toInstant(), 400, "DOCTOR_ON_LEAVE"},
                {hol.atTime(10, 0).atZone(c.zone).toInstant(), 400, "CLINIC_CLOSED"}};
        for (Object[] k : cases) {
            ResponseEntity<Map> bk = book(c, patient(c.token), (Instant) k[0]);
            ResponseEntity<Map> re = exchange("/v1/appointments/" + id + "/reschedule", HttpMethod.POST, authedJsonBody(c.token, Map.of("newStartTime", k[0].toString())), Map.class);
            ResponseEntity<Map> wl = exchange("/v1/waitlist/" + entry + "/accept", HttpMethod.POST, authedJsonBody(c.token, Map.of("slotStart", k[0].toString())), Map.class);
            for (ResponseEntity<Map> r : List.of(bk, re, wl)) {
                assertThat(r.getStatusCode().value()).as(k[2] + " " + r.getBody()).isEqualTo(k[1]);
                assertThat(r.getBody().get("code")).as(String.valueOf(k[0])).isEqualTo(k[2]);
            }
        }
        assertThat(exchange("/v1/appointments/" + id, HttpMethod.GET, authed(c.token), Map.class).getBody().get("status")).isEqualTo("scheduled");
        assertThat(exchange("/v1/appointments/" + id, HttpMethod.GET, authed(c.token), Map.class).getBody().get("startTime").toString()).contains(c.at("10:00").toString().substring(0, 16));
    }

    // ---------- AC5 ----------
    @Test
    void ac5_cancelledSlotRebooksImmediately_andRescheduleFreesTheOldSlot() {
        Clinic c = clinic("Asia/Kolkata");
        String first = (String) book(c, c.at("10:00")).getBody().get("id");
        assertThat(exchange("/v1/appointments/" + first + "/cancel", HttpMethod.POST, authedJsonBody(c.token, Map.of("reason", "changed mind")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> again = book(c, patient(c.token), c.at("10:00"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) again.getBody().get("id");
        assertThat(exchange("/v1/appointments/" + id + "/reschedule", HttpMethod.POST, authedJsonBody(c.token, Map.of("newStartTime", c.at("11:00").toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(book(c, patient(c.token), c.at("10:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void dbConstraint_cancelledAndTokenKindDoNotHoldSlots_arrivedDoes() throws Exception {
        Clinic c = clinic("Asia/Kolkata");
        assertThat(book(c, c.at("10:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ins = "INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, booking_kind, cancel_reason) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection su = superuserConnection()) {
            // token-mode and cancelled rows overlap freely
            for (String[] k : new String[][]{{"scheduled", "token", null}, {"cancelled", "slot", "x"}}) {
                try (PreparedStatement ps = su.prepareStatement(ins)) {
                    ps.setObject(1, UUID.randomUUID()); ps.setObject(2, c.tenantId); ps.setObject(3, UUID.fromString(c.patient)); ps.setObject(4, c.doctor);
                    ps.setTimestamp(5, Timestamp.from(c.at("10:05"))); ps.setTimestamp(6, Timestamp.from(c.at("10:20")));
                    ps.setString(7, k[0]); ps.setString(8, k[1]); ps.setString(9, k[2]);
                    assertThat(ps.executeUpdate()).as(k[0] + "/" + k[1]).isEqualTo(1);
                }
            }
            // an 'arrived' slot row overlapping a scheduled one is refused
            assertThatThrownBy(() -> { try (PreparedStatement ps = su.prepareStatement(ins)) {
                ps.setObject(1, UUID.randomUUID()); ps.setObject(2, c.tenantId); ps.setObject(3, UUID.fromString(c.patient)); ps.setObject(4, c.doctor);
                ps.setTimestamp(5, Timestamp.from(c.at("10:05"))); ps.setTimestamp(6, Timestamp.from(c.at("10:20")));
                ps.setString(7, "arrived"); ps.setString(8, "slot"); ps.setString(9, null); ps.executeUpdate(); } })
                    .hasMessageContaining("ex_appointments_doctor_overlap");
        }
    }

    // ---------- AC6 property ----------
    @Test
    void ac6_property_1000RandomTimesPerTimezone_availabilityEqualsBookable() {
        for (String tz : List.of("Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh", "Asia/Qatar")) {
            Clinic c = clinic(tz);
            hours(c.token, c.doctor, c.day, "14:00:00", "17:00:00", 20); // second block, different slot length
            Set<Instant> slots = new HashSet<>();
            for (Object o : exchange("/v1/doctors/" + c.doctor + "/availability?date=" + c.day, HttpMethod.GET, authed(c.token), List.class).getBody()) {
                slots.add(Instant.parse((String) o));
            }
            assertThat(slots).as(tz + " availability size").hasSize(16 + 9);
            Random rnd = new Random(7);
            Instant midnight = c.day.atStartOfDay(c.zone).toInstant();
            Set<Instant> booked = new HashSet<>();
            int refused = 0;
            for (int i = 0; i < 1000; i++) {
                int minute = i % 2 == 0 ? 8 * 60 + rnd.nextInt(10 * 60) : rnd.nextInt(1440);
                Instant t = midnight.plusSeconds(minute * 60L + (rnd.nextInt(10) == 0 ? 17 : 0));
                ResponseEntity<Map> r = book(c, patient(c.token), t);
                int s = r.getStatusCode().value();
                if (slots.contains(t) && booked.add(t)) {
                    assertThat(s).as(tz + " listed slot " + t + " " + r.getBody()).isEqualTo(201);
                } else if (slots.contains(t)) {
                    assertThat(r.getBody().get("code")).as(tz + " repeat " + t).isEqualTo("SLOT_TAKEN");
                } else {
                    assertThat(s).as(tz + " unlisted time " + t).isBetween(400, 409);
                    assertThat(s).isNotEqualTo(201);
                    refused++;
                }
            }
            for (Instant slot : slots) {
                if (booked.add(slot)) {
                    assertThat(book(c, patient(c.token), slot).getStatusCode()).as(tz + " remaining listed slot " + slot).isEqualTo(HttpStatus.CREATED);
                }
            }
            assertThat(refused).as(tz + " unlisted draws").isGreaterThan(300);
            // everything listed is now taken
            assertThat(exchange("/v1/doctors/" + c.doctor + "/availability?date=" + c.day, HttpMethod.GET, authed(c.token), List.class).getBody()).as(tz + " all taken").isEmpty();
        }
    }

    // ---------- AC7 ----------
    @Test
    void ac7_bookingWriteP95Under400ms() {
        Clinic c = clinic("Asia/Kolkata");
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < 16; i++) ps.add(patient(c.token));
        List<Long> lat = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Instant t = c.at(String.format("%02d:%02d", 9 + i / 4, (i % 4) * 15));
            long t0 = System.nanoTime();
            ResponseEntity<Map> r = book(c, ps.get(i), t);
            lat.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        Collections.sort(lat);
        long p95 = lat.get((int) Math.ceil(lat.size() * 0.95) - 1);
        System.out.println("S10QA ACC-004 sequential booking latencies ms: p50=" + lat.get(lat.size() / 2) + " p95=" + p95 + " max=" + lat.get(lat.size() - 1));
        assertThat(p95).as("booking p95 ms").isLessThan(400);
    }

    // ---------- contract extras ----------
    @Test
    void idempotencyKeyReplayOnBooking_returnsSameAppointment() {
        Clinic c = clinic("Asia/Kolkata");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON); h.setBearerAuth(c.token); h.set("Idempotency-Key", UUID.randomUUID().toString());
        var e = new org.springframework.http.HttpEntity<>("{\"doctorId\":\"" + c.doctor + "\",\"patientId\":\"" + c.patient + "\",\"startTime\":\"" + c.at("10:00") + "\"}", h);
        ResponseEntity<Map> a = exchange("/v1/appointments", HttpMethod.POST, e, Map.class);
        ResponseEntity<Map> b = exchange("/v1/appointments", HttpMethod.POST, e, Map.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).as("replay " + b.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getBody().get("id")).isEqualTo(a.getBody().get("id"));
    }

    @Test
    void crossTenantDoctor_cannotBeBooked() {
        Clinic a = clinic("Asia/Kolkata");
        Clinic b = clinic("Asia/Kolkata");
        ResponseEntity<Map> r = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(b.token, Map.of(
                "doctorId", a.doctor.toString(), "patientId", b.patient, "startTime", a.at("10:00").toString())), Map.class);
        assertThat(r.getStatusCode().value()).isIn(400, 404);
        assertThat(inTenantTx(a.tenantId, () -> jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))).isZero();
    }

    @Test
    // known defect (ACC-004): appointment.booked is not yet published to the outbox
    @Tag("known-defect-ACC-004")
    void event_appointmentBookedPublishedToOutbox() {
        Clinic c = clinic("Asia/Kolkata");
        String id = (String) book(c, c.at("10:00")).getBody().get("id");
        Integer n = inTenantTx(c.tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'scheduling.appointment.booked.v1' AND payload->>'appointmentId' = ?", Integer.class, id));
        assertThat(n).isEqualTo(1);
    }
}
