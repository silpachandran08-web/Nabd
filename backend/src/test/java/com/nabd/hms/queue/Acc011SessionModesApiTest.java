package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-011: a session runs as slots, tokens or both; online cap vs desk cap; capacity under the doctor-day lock. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Acc011SessionModesApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(7000);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private record Ctx(SeededTenant tenant, SeededStaff doctor, String token, LocalDate today) {
        int dow(LocalDate d) {
            return d.getDayOfWeek().getValue() % 7;
        }

        Instant at(LocalDate d, String hhmm) {
            return d.atTime(java.time.LocalTime.parse(hhmm)).atZone(ZONE).toInstant();
        }
    }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), tenant.id());
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "acc11" + n + "@a.com", "+9195" + (3000000 + n), false);
        return new Ctx(tenant, doctor, loginAndGetAccessToken(doctor), LocalDate.now(ZONE));
    }

    private String patient(Ctx c) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+91996" + (3000000 + n),
                "dob", "1985-05-05", "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> hours(Ctx c, LocalDate day, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("dayOfWeek", c.dow(day), "startTime", "00:00:00", "endTime", "23:59:00", "slotMinutes", 15));
        body.putAll(extra);
        return exchange("/v1/doctors/" + c.doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token, body), Map.class);
    }

    private ResponseEntity<Map> book(Ctx c, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "doctorId", c.doctor.id().toString(), "patientId", patient(c), "startTime", start.toString())), Map.class);
    }

    private ResponseEntity<Map> walkIn(Ctx c) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "patientId", patient(c), "doctorId", c.doctor.id().toString())), Map.class);
    }

    @Test
    void onlineCapStopsBookingsWhileTheDeskKeepsAddingWalkInsUpToTheSessionMax() {
        Ctx c = ctx();
        assertThat(hours(c, c.today, Map.of("sessionMode", "token", "maxPatients", 5, "onlineCap", 3, "label", "Evening OPD"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<Map> r = book(c, c.at(c.today, "12:00"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(r.getBody().get("bookingKind")).isEqualTo("token");
            assertThat(r.getBody().get("sessionToken")).isEqualTo(i);
            // an expected range from the ETA engine, never a fixed slot time
            assertThat((String) r.getBody().get("expectedRange")).matches("\\d\\d:\\d\\d-\\d\\d:\\d\\d");
        }
        ResponseEntity<Map> full = book(c, c.at(c.today, "12:00"));
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(full.getBody().get("code")).isEqualTo("SESSION_FULL");
        assertThat(full.getBody().get("scope")).isEqualTo("online");
        // the desk can still add walk-ins up to max 5 (3 bookings + 2), then it is full for everyone
        assertThat(walkIn(c).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(walkIn(c).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> deskFull = walkIn(c);
        assertThat(deskFull.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deskFull.getBody().get("code")).isEqualTo("SESSION_FULL");
    }

    @Test
    void cancelledTokenIsReissuedAndSessionsAvailabilityShowsPlacesAndRange() {
        Ctx c = ctx();
        hours(c, c.today, Map.of("sessionMode", "token", "maxPatients", 4, "onlineCap", 4, "label", "Evening OPD"));
        String first = (String) book(c, c.at(c.today, "10:00")).getBody().get("id");
        book(c, c.at(c.today, "10:00"));
        ResponseEntity<Map> av = exchange("/v1/doctors/" + c.doctor.id() + "/availability/sessions?date=" + c.today, HttpMethod.GET,
                authed(c.token), Map.class);
        Map session = (Map) ((List) av.getBody().get("sessions")).get(0);
        assertThat(session.get("mode")).isEqualTo("token");
        assertThat(session.get("label")).isEqualTo("Evening OPD");
        assertThat(session.get("tokensLeft")).isEqualTo(2);
        assertThat(session.get("slots")).isNull();
        assertThat((String) session.get("expectedRange")).matches("\\d\\d:\\d\\d-\\d\\d:\\d\\d");
        // the plain slot list offers nothing for a token session
        assertThat(exchange("/v1/doctors/" + c.doctor.id() + "/availability?date=" + c.today, HttpMethod.GET, authed(c.token), List.class)
                .getBody()).isEmpty();

        exchange("/v1/appointments/" + first + "/cancel", HttpMethod.POST, authedJsonBody(c.token, Map.of("reasonCode", "illness")), Map.class);
        assertThat(book(c, c.at(c.today, "10:00")).getBody().get("sessionToken")).isEqualTo(1); // lowest free number
    }

    @Test
    void checkInIssuesTheReservedNumberIfFreeElseTheNext() {
        Ctx c = ctx();
        hours(c, c.today, Map.of("sessionMode", "token", "maxPatients", 10));
        // a walk-in takes queue token 1 before the token holder (reserved 1) arrives
        walkIn(c);
        ResponseEntity<Map> booked = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "doctorId", c.doctor.id().toString(), "patientId", patient(c), "startTime", c.at(c.today, "12:00").toString())), Map.class);
        ResponseEntity<Map> in = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "patientId", booked.getBody().get("patientId"), "doctorId", c.doctor.id().toString(),
                "appointmentId", booked.getBody().get("id"))), Map.class);
        assertThat(in.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(in.getBody().get("tokenNumber")).isEqualTo(2); // reserved 1 was taken: next
    }

    @Test
    void hybridSessionLastPlaceRaceHasExactlyOneWinner() throws Exception {
        Ctx c = ctx();
        Assumptions.assumeTrue(LocalDateTime.now(ZONE).getHour() < 21, "needs future slots today");
        assertThat(hours(c, c.today, Map.of("sessionMode", "hybrid", "maxPatients", 3)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Instant base = Instant.now().plusSeconds(3600).atZone(ZONE).withMinute(0).withSecond(0).withNano(0).toInstant();
        assertThat(book(c, base.plusSeconds(15 * 60)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(book(c, base.plusSeconds(30 * 60)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String bookPatient = patient(c), walkPatient = patient(c);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<ResponseEntity<Map>> b = pool.submit(() -> {
                go.await();
                return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                        "doctorId", c.doctor.id().toString(), "patientId", bookPatient, "startTime", base.plusSeconds(45 * 60).toString())), Map.class);
            });
            Future<ResponseEntity<Map>> w = pool.submit(() -> {
                go.await();
                return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                        "patientId", walkPatient, "doctorId", c.doctor.id().toString())), Map.class);
            });
            go.countDown();
            List<HttpStatus> codes = new ArrayList<>(List.of((HttpStatus) b.get(60, TimeUnit.SECONDS).getStatusCode(),
                    (HttpStatus) w.get(60, TimeUnit.SECONDS).getStatusCode()));
            assertThat(codes).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void modeChangeAppliesToFutureDatesOnlyAndSlotSessionsStayAsBefore() {
        Ctx c = ctx();
        LocalDate d = c.today.plusDays(3), later = d.plusDays(7);
        Map hours = hours(c, d, Map.of("startTime", "09:00:00", "endTime", "13:00:00")).getBody();
        assertThat(hours.get("sessionMode")).isEqualTo("slot");
        ResponseEntity<Map> existing = book(c, c.at(d, "10:00"));
        assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(existing.getBody().get("bookingKind")).isEqualTo("slot");

        Map<String, Object> put = new HashMap<>(Map.of("dayOfWeek", c.dow(d), "startTime", "09:00:00", "endTime", "13:00:00", "slotMinutes", 15,
                "maxPatients", 20, "sessionMode", "token", "onlineCap", 10));
        String url = "/v1/doctors/" + c.doctor.id() + "/working-hours/" + hours.get("id");
        put.put("effectiveFrom", c.today.toString()); // not a future date
        assertThat(exchange(url, HttpMethod.PUT, authedJsonBody(c.token, put), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        put.put("effectiveFrom", later.toString());
        ResponseEntity<Map> changed = exchange(url, HttpMethod.PUT, authedJsonBody(c.token, put), Map.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("sessionMode")).isEqualTo("token");

        // before the change date: still slots, the existing booking is untouched
        ResponseEntity<Map> stillSlot = book(c, c.at(d, "10:15"));
        assertThat(stillSlot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stillSlot.getBody().get("bookingKind")).isEqualTo("slot");
        assertThat(exchange("/v1/appointments/" + existing.getBody().get("id"), HttpMethod.GET, authed(c.token), Map.class)
                .getBody().get("startTime")).isEqualTo(existing.getBody().get("startTime"));
        // from the change date: tokens
        ResponseEntity<Map> token = book(c, c.at(later, "10:00"));
        assertThat(token.getBody().get("bookingKind")).isEqualTo("token");
        assertThat(token.getBody().get("sessionToken")).isEqualTo(1);
    }

    @Test
    void tokenAndHybridNeedMaxPatientsAndOnlineCapCannotExceedIt() {
        Ctx c = ctx();
        assertThat(hours(c, c.today, Map.of("sessionMode", "token")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(hours(c, c.today, Map.of("sessionMode", "hybrid")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(hours(c, c.today, Map.of("sessionMode", "token", "maxPatients", 5, "onlineCap", 6)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(hours(c, c.today, Map.of("sessionMode", "bogus", "maxPatients", 5)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // boundary: a cap equal to the session size is allowed
        assertThat(hours(c, c.today, Map.of("sessionMode", "token", "maxPatients", 5, "onlineCap", 5)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }
}
