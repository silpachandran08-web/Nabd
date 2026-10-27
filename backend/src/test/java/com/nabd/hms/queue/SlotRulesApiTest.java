package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.support.ApiTestBase;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ACC-004: one SlotValidator, one exclusion constraint; every refusal has a named Problem type and code. */
class SlotRulesApiTest extends ApiTestBase {

    @Autowired
    SlotValidator validator;

    private record Clinic(UUID tenantId, String token, UUID doctor, String patient, LocalDate day, ZoneId zone) {
        Instant at(String hhmm) {
            return day.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant();
        }
    }

    /** Doctor works 09:00-13:00 on 15-minute slots on a day three days ahead (clinic wall clock in tz). */
    private Clinic clinic(String tz) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", tz, tenant.id());
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "d" + sfx + "@a.com",
                "+9195" + (10000000 + new Random().nextInt(8999999)), false);
        String token = loginAndGetAccessToken(doctor);
        ZoneId zone = ZoneId.of(tz);
        LocalDate day = LocalDate.now(zone).plusDays(3);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", day.getDayOfWeek().getValue() % 7, "startTime", "09:00:00", "endTime", "13:00:00",
                "slotMinutes", 15)), Map.class);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "P " + sfx, "phone", "+9194" + (10000000 + new Random().nextInt(8999999)), "dob", "1990-01-01",
                "gender", "male")), Map.class).getBody().get("id");
        return new Clinic(tenant.id(), token, doctor.id(), patient, day, zone);
    }

    private ResponseEntity<Map> book(Clinic c, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "doctorId", c.doctor().toString(), "patientId", c.patient(), "startTime", start.toString())), Map.class);
    }

    private static void assertProblem(ResponseEntity<Map> r, HttpStatus status, String slug, String code) {
        assertThat(r.getStatusCode()).isEqualTo(status);
        assertThat(r.getBody().get("type")).asString().endsWith("/" + slug);
        assertThat(r.getBody().get("code")).isEqualTo(code);
    }

    @Test
    void overlappingStartIsSlotTakenAndCancelledSlotFreesImmediately() {
        Clinic c = clinic("Asia/Kolkata");
        ResponseEntity<Map> first = book(c, c.at("10:00"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(book(c, c.at("10:00")), HttpStatus.CONFLICT, "slot-taken", "SLOT_TAKEN");
        // 10:05 is off the grid (400) at the API; the database itself refuses the overlap too
        assertProblem(book(c, c.at("10:05")), HttpStatus.BAD_REQUEST, "off-grid", "OFF_GRID");
        assertThatThrownBy(() -> inTenantTx(c.tenantId(), () -> jdbc.update(
                "INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), c.tenantId(), UUID.fromString(c.patient()), c.doctor(),
                java.sql.Timestamp.from(c.at("10:05")), java.sql.Timestamp.from(c.at("10:20")))))
                .hasMessageContaining("ex_appointments_doctor_overlap");
        // back-to-back is not an overlap
        assertThat(book(c, c.at("10:15")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String id = (String) first.getBody().get("id");
        exchange("/v1/appointments/" + id + "/cancel", HttpMethod.POST, authedJsonBody(c.token(), Map.of("reasonCode", "illness")), Map.class);
        assertThat(book(c, c.at("10:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void twentyConcurrentBookingsOfTheSameTimeYieldExactlyOneSuccess() throws Exception {
        Clinic c = clinic("Asia/Dubai");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<ResponseEntity<Map>>> jobs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                jobs.add(() -> book(c, c.at("11:00")));
            }
            int created = 0, taken = 0;
            for (Future<ResponseEntity<Map>> f : pool.invokeAll(jobs)) {
                ResponseEntity<Map> r = f.get();
                if (r.getStatusCode() == HttpStatus.CREATED) {
                    created++;
                } else if (r.getStatusCode() == HttpStatus.CONFLICT && "SLOT_TAKEN".equals(r.getBody().get("code"))) {
                    taken++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(taken).isEqualTo(19);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void everyRefusalHasItsOwnProblemType() {
        Clinic c = clinic("Asia/Riyadh");
        assertProblem(book(c, c.at("14:00")), HttpStatus.BAD_REQUEST, "outside-hours", "OUTSIDE_HOURS");
        assertProblem(book(c, c.at("10:07")), HttpStatus.BAD_REQUEST, "off-grid", "OFF_GRID");
        // boundary: 12:45 is the last 15-minute slot of a block ending 13:00; 13:00 itself is outside
        assertThat(book(c, c.at("12:45")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(book(c, c.at("13:00")), HttpStatus.BAD_REQUEST, "outside-hours", "OUTSIDE_HOURS");
        // same weekday last week: inside the hours and on the grid, only the past is wrong
        assertProblem(book(c, c.day().minusDays(7).atTime(10, 0).atZone(c.zone()).toInstant()),
                HttpStatus.BAD_REQUEST, "in-past", "IN_PAST");
    }

    @Test
    void doctorOnLeaveAndClinicHolidayAreRefused() {
        Clinic c = clinic("Asia/Qatar");
        exchange("/v1/doctors/" + c.doctor() + "/leave", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "dateFrom", c.day().toString(), "dateTo", c.day().toString(), "reason", "conference")), Map.class);
        assertProblem(book(c, c.at("11:00")), HttpStatus.BAD_REQUEST, "doctor-on-leave", "DOCTOR_ON_LEAVE");

        LocalDate holiday = c.day().plusDays(7); // same weekday, so the doctor works that day
        exchange("/v1/setup/holidays", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "holidayDate", holiday.toString(), "name", "Closed", "recurring", false)), Map.class);
        assertProblem(book(c, holiday.atTime(10, 0).atZone(c.zone()).toInstant()),
                HttpStatus.BAD_REQUEST, "clinic-closed", "CLINIC_CLOSED");
    }

    @Test
    void rescheduleAndWaitlistChosenSlotReturnTheSameErrorsAsBooking() {
        Clinic c = clinic("Asia/Kolkata");
        String id = (String) book(c, c.at("10:00")).getBody().get("id");
        String other = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", "Other", "phone", "+919477700001", "dob", "1985-02-02", "gender", "female")), Map.class).getBody().get("id");
        assertThat(exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "doctorId", c.doctor().toString(), "patientId", other, "startTime", c.at("10:15").toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String entry = (String) exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "doctorId", c.doctor().toString(), "patientId", other)), Map.class).getBody().get("id");

        Instant past = c.day().minusDays(7).atTime(10, 0).atZone(c.zone()).toInstant();
        Object[][] cases = {
                {c.at("10:07"), "off-grid", "OFF_GRID", 400}, {c.at("14:00"), "outside-hours", "OUTSIDE_HOURS", 400},
                {past, "in-past", "IN_PAST", 400}, {c.at("10:15"), "slot-taken", "SLOT_TAKEN", 409}};
        for (Object[] k : cases) {
            ResponseEntity<Map> re = exchange("/v1/appointments/" + id + "/reschedule", HttpMethod.POST,
                    authedJsonBody(c.token(), Map.of("newStartTime", k[0].toString())), Map.class);
            ResponseEntity<Map> wl = exchange("/v1/waitlist/" + entry + "/accept", HttpMethod.POST,
                    authedJsonBody(c.token(), Map.of("slotStart", k[0].toString())), Map.class);
            assertProblem(re, HttpStatus.valueOf((int) k[3]), (String) k[1], (String) k[2]);
            assertProblem(wl, HttpStatus.valueOf((int) k[3]), (String) k[1], (String) k[2]);
        }
        // a refused reschedule leaves the original booking in place
        assertThat(exchange("/v1/appointments/" + id, HttpMethod.GET, authed(c.token()), Map.class).getBody().get("status"))
                .isEqualTo("scheduled");
        // and a valid reschedule still works
        assertThat(exchange("/v1/appointments/" + id + "/reschedule", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("newStartTime", c.at("11:30").toString())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** Property: for 1,000 random times per timezone, the validator accepts exactly the availability slots, and each books. */
    @Test
    void availabilityAndBookingAgreeInEveryTimezone() {
        for (String tz : List.of("Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh", "Asia/Qatar")) {
            Clinic c = clinic(tz);
            ResponseEntity<List> av = exchange("/v1/doctors/" + c.doctor() + "/availability?date=" + c.day(),
                    HttpMethod.GET, authed(c.token()), List.class);
            Set<Instant> slots = new HashSet<>();
            for (Object o : av.getBody()) {
                slots.add(Instant.parse((String) o));
            }
            assertThat(slots).as(tz).hasSize(16);

            Random rnd = new Random(42);
            Instant midnight = c.day().atStartOfDay(c.zone()).toInstant();
            Instant now = Instant.now();
            int[] accepted = {0};
            inTenantTx(c.tenantId(), () -> {
                for (int i = 0; i < 1000; i++) {
                    // bias half the picks into the working window so both outcomes are well covered
                    int minute = i % 2 == 0 ? 8 * 60 + rnd.nextInt(6 * 60) : rnd.nextInt(1440);
                    Instant t = midnight.plusSeconds(minute * 60L + (rnd.nextInt(10) == 0 ? 17 : 0));
                    boolean ok;
                    try {
                        validator.validate(c.tenantId(), c.doctor(), t, now);
                        ok = true;
                    } catch (ApiException e) {
                        ok = false;
                    }
                    assertThat(ok).as(tz + " " + t).isEqualTo(slots.contains(t));
                    accepted[0] += ok ? 1 : 0;
                }
            });
            assertThat(accepted[0]).as(tz + " accepted some").isGreaterThan(0);
            for (Instant slot : slots) {
                assertThat(book(c, slot).getStatusCode()).as(tz + " " + slot).isEqualTo(HttpStatus.CREATED);
            }
        }
    }

    @Test
    void tenantsAreIsolatedByTheConstraint() {
        Clinic a = clinic("Asia/Kolkata");
        Clinic b = clinic("Asia/Kolkata");
        assertThat(book(a, a.at("10:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // the other tenant's doctor at the same wall-clock time is a different (tenant, doctor) key
        assertThat(book(b, b.at("10:00")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
