package com.nabd.hms.queue;

import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-005: an evening session that runs past midnight is ONE clinic day (day start 04:00 by default), and a doctor can take
 * part of a day off. The clock is frozen (PLT-028), so nothing here flakes near real midnight. 2026-03-10 is a Tuesday (day_of_week 2).
 */
@Import(ClinicDayCheckInClockApiTest.FrozenClock.class)
class Acc005ClinicDayApiTest extends ApiTestBase {

    @Autowired
    MutableClock clock;
    @Autowired
    JwtEncoder encoder;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private record Clinic(SeededTenant tenant, SeededStaff doc, String token, String zone) {
    }

    private Clinic clinic(String zone) {
        return clinic(zone, 240);
    }

    private Clinic clinic(String zone, int dayStartMinute) {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ?, day_start_minute = ? WHERE id = ?", zone, dayStartMinute, t.id()); // before the first login caches the row
        String tag = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "a5" + tag + "@a.com", "+9197" + (10000000 + Math.abs(tag.hashCode() % 89999999)), false);
        return new Clinic(t, doc, loginAndGetAccessToken(doc), zone); // login at real time: the JWT decoder uses the system clock
    }

    private ResponseEntity<Map> addHours(Clinic c, int dow, String start, String end, Integer cap) {
        java.util.Map<String, Object> body = new java.util.HashMap<>(Map.of("dayOfWeek", dow, "startTime", start, "endTime", end, "slotMinutes", 15));
        if (cap != null) body.put("maxPatients", cap);
        return exchange("/v1/doctors/" + c.doc().id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private String patient(Clinic c, String name) {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name + " " + tag.replaceAll("[0-9]", "x"), "phone", "+9193" + (10000000 + Math.abs(tag.hashCode() % 89999999)),
                "dob", "1984-04-04", "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> checkIn(Clinic c, String patientId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.doc().id().toString())), Map.class);
    }

    private ResponseEntity<Map> book(Clinic c, String patientId, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "doctorId", c.doc().id().toString(), "patientId", patientId, "startTime", start.toString())), Map.class);
    }

    private static Instant at(String localDateTime, String zone) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(ZoneId.of(zone)).toInstant();
    }

    // AC1, AC2, AC3, AC6: the Tuesday 20:00-01:00 session, checked in at 23:50 and 00:10, in four clinic zones
    @ParameterizedTest
    @CsvSource({"Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh", "Asia/Qatar"})
    @SuppressWarnings("unchecked")
    void anEveningSessionAcrossMidnightIsOneClinicDay(String zone) {
        Clinic c = clinic(zone);
        ResponseEntity<Map> hours = addHours(c, 2, "20:00:00", "01:00:00", 3);
        assertThat(hours.getStatusCode()).as(String.valueOf(hours.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(hours.getBody()).containsEntry("endsNextDay", true);
        String p1 = patient(c, "One"), p2 = patient(c, "Two"), p3 = patient(c, "Three"), p4 = patient(c, "Four");

        clock.setLocal("2026-03-10T23:50:00", zone);
        ResponseEntity<Map> first = checkIn(c, p1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).containsEntry("queueDate", "2026-03-10").containsEntry("tokenNumber", 1);

        clock.setLocal("2026-03-11T00:10:00", zone); // the calendar date moved on, the clinic date did not
        ResponseEntity<Map> second = checkIn(c, p2);
        assertThat(second.getBody()).containsEntry("queueDate", "2026-03-10").containsEntry("tokenNumber", 2);

        // AC2: at 00:30 the board defaults to the clinic date that began the previous calendar day and shows both
        clock.setLocal("2026-03-11T00:30:00", zone);
        List<Map<String, Object>> board = exchange("/v1/queue", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(board).hasSize(2).extracting(e -> e.get("tokenNumber")).containsExactlyInAnyOrder(1, 2);
        assertThat(board).extracting(e -> e.get("queueDate")).containsOnly("2026-03-10");

        // AC3: a 00:30 walk-in is the third place of the cap-3 evening session, the next one is refused
        assertThat(checkIn(c, p3).getBody()).containsEntry("queueDate", "2026-03-10").containsEntry("tokenNumber", 3);
        clock.setLocal("2026-03-11T00:40:00", zone);
        ResponseEntity<Map> full = checkIn(c, p4);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(full.getBody()).containsEntry("code", "SESSION_FULL");

        // day close: at 00:40 the day to close is still the 10th; the 11th has not started
        ResponseEntity<Map> summary = exchange("/v1/billing/day-close", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(summary.getBody()).containsEntry("date", "2026-03-10").containsEntry("today", true);
        ResponseEntity<Map> future = exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(c.token(),
                Map.of("countedCash", 0, "date", "2026-03-11")), Map.class);
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> closed = exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(c.token(), Map.of("countedCash", 0)), Map.class);
        assertThat(closed.getStatusCode()).as(String.valueOf(closed.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody()).containsEntry("date", "2026-03-10");

        // the day start: 03:59 is still the 10th, 04:00 is the 11th with token numbering restarting at 1
        clock.setLocal("2026-03-11T03:59:59", zone);
        assertThat(exchange("/v1/schedule/day", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("date", "2026-03-10");
        clock.setLocal("2026-03-11T04:00:00", zone);
        assertThat(exchange("/v1/schedule/day", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("date", "2026-03-11");
        ResponseEntity<Map> morning = checkIn(c, p4);
        assertThat(morning.getBody()).containsEntry("queueDate", "2026-03-11").containsEntry("tokenNumber", 1);
    }

    // a booking at 00:30 belongs to the evening session; the grid labels it "(+1)" and availability offers it
    @Test
    @SuppressWarnings("unchecked")
    void aBookingAfterMidnightIsOnTheEveningSessionAndTheGridLabelsItPlusOne() {
        Clinic c = clinic("Asia/Dubai");
        assertThat(addHours(c, 2, "20:00:00", "01:00:00", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        clock.setLocal("2026-03-09T12:00:00", c.zone());
        List<String> slots = exchange("/v1/doctors/" + c.doc().id() + "/availability?date=2026-03-10", HttpMethod.GET, authed(c.token()), List.class).getBody();
        Instant half = at("2026-03-11T00:30:00", c.zone());
        assertThat(slots).contains(half.toString(), at("2026-03-10T20:00:00", c.zone()).toString()).hasSize(20); // 5 h of 15-minute slots
        assertThat(slots).doesNotContain(at("2026-03-11T01:00:00", c.zone()).toString());

        String p = patient(c, "Night Owl");
        assertThat(book(c, p, half).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> day = exchange("/v1/schedule/day?date=2026-03-10", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) day.get("sessions");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0)).containsEntry("start", "20:00").containsEntry("end", "01:00 (+1)");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) sessions.get(0).get("rows");
        Map<String, Object> row = rows.stream().filter(r -> "00:30 (+1)".equals(r.get("time"))).findFirst().orElseThrow();
        assertThat(((List<Map<String, Object>>) row.get("cells")).get(0)).containsEntry("kind", "booked");
        // 01:00 is outside the session
        assertThat(book(c, p, at("2026-03-11T01:00:00", c.zone())).getBody()).containsEntry("code", "OUTSIDE_HOURS");
    }

    // AC5
    @Test
    void overnightHoursMustEndByTheDayStart() {
        Clinic c = clinic("Asia/Dubai");
        ResponseEntity<Map> ok = addHours(c, 1, "18:00:00", "02:00:00", null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody()).containsEntry("endsNextDay", true);
        ResponseEntity<Map> bad = addHours(c, 3, "18:00:00", "05:00:00", null);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).containsEntry("code", "SESSION_CROSSES_DAY_START");
        // boundary: ending exactly at the day start is allowed; an empty block is not
        assertThat(addHours(c, 3, "18:00:00", "04:00:00", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(addHours(c, 4, "09:00:00", "09:00:00", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // an ordinary daytime block is unchanged and does not end on the next day
        assertThat(addHours(c, 5, "09:00:00", "13:00:00", null).getBody()).containsEntry("endsNextDay", false);
        // overlap is judged on the clinic day: 23:00-03:00 collides with 18:00-02:00 on Monday
        ResponseEntity<Map> overlap = addHours(c, 1, "23:00:00", "03:00:00", null);
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overlap.getBody().get("type")).asString().endsWith("/hours-overlap");
    }

    // AC4
    @Test
    @SuppressWarnings("unchecked")
    void partDayLeaveHidesItsSlotsAndRefusesBookingsInsideIt() {
        Clinic c = clinic("Asia/Kolkata");
        LocalDate day = LocalDate.now(ZoneId.of(c.zone())).plusDays(3);
        assertThat(addHours(c, day.getDayOfWeek().getValue() % 7, "09:00:00", "18:00:00", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String morning = patient(c, "Morning"), afternoon = patient(c, "Afternoon");
        ZoneId zone = ZoneId.of(c.zone());
        Instant tenAm = day.atTime(10, 0).atZone(zone).toInstant(), threePm = day.atTime(15, 0).atZone(zone).toInstant();
        assertThat(book(c, morning, tenAm).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> existing = book(c, afternoon, threePm);
        assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> leave = exchange("/v1/doctors/" + c.doc().id() + "/leave", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "dateFrom", day.toString(), "dateTo", day.toString(), "startTime", "14:00:00", "endTime", "17:00:00", "reason", "errand")), Map.class);
        assertThat(leave.getStatusCode()).as(String.valueOf(leave.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(leave.getBody()).containsEntry("startTime", "14:00:00").containsEntry("endTime", "17:00:00");
        List<Map<String, Object>> affected = (List<Map<String, Object>>) leave.getBody().get("affectedAppointments");
        assertThat(affected).hasSize(1); // only the 15:00 booking, not the 10:00 one
        assertThat(affected.get(0)).containsEntry("id", existing.getBody().get("id"));
        assertThat(Instant.parse((String) affected.get(0).get("start"))).isEqualTo(threePm);

        List<String> slots = exchange("/v1/doctors/" + c.doc().id() + "/availability?date=" + day, HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(slots).doesNotContain(day.atTime(14, 0).atZone(zone).toInstant().toString(), day.atTime(14, 45).atZone(zone).toInstant().toString(),
                day.atTime(16, 45).atZone(zone).toInstant().toString());
        assertThat(slots).contains(day.atTime(13, 45).atZone(zone).toInstant().toString(), day.atTime(17, 0).atZone(zone).toInstant().toString(),
                day.atTime(11, 0).atZone(zone).toInstant().toString());

        ResponseEntity<Map> refused = book(c, morning, day.atTime(15, 30).atZone(zone).toInstant());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "DOCTOR_ON_LEAVE");
        assertThat(book(c, morning, day.atTime(13, 45).atZone(zone).toInstant()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(book(c, morning, day.atTime(11, 0).atZone(zone).toInstant()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // the grid shows the leave inside the doctor's hours
        Map<String, Object> grid = exchange("/v1/schedule/day?date=" + day, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        String kinds = ((List<Map<String, Object>>) grid.get("sessions")).stream().flatMap(s -> ((List<Map<String, Object>>) s.get("rows")).stream())
                .filter(r -> "16:00".equals(r.get("time"))).map(r -> String.valueOf(((List<Map<String, Object>>) r.get("cells")).get(0).get("kind"))).findFirst().orElse("");
        assertThat(kinds).isEqualTo("leave");
    }

    @Test
    void leaveWindowIsValidated() {
        Clinic c = clinic("Asia/Dubai");
        String url = "/v1/doctors/" + c.doc().id() + "/leave";
        // only one of the two times; an empty window; part-day over several days
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("dateFrom", "2031-05-05", "dateTo", "2031-05-05", "startTime", "14:00:00"),
                Map.of("dateFrom", "2031-05-05", "dateTo", "2031-05-05", "startTime", "14:00:00", "endTime", "14:00:00"),
                Map.of("dateFrom", "2031-05-05", "dateTo", "2031-05-06", "startTime", "14:00:00", "endTime", "16:00:00"))) {
            assertThat(exchange(url, HttpMethod.POST, authedJsonBody(c.token(), body), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        // a whole-day leave still works and blocks the whole date
        ResponseEntity<Map> whole = exchange(url, HttpMethod.POST, authedJsonBody(c.token(), Map.of("dateFrom", "2031-05-05", "dateTo", "2031-05-06")), Map.class);
        assertThat(whole.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(whole.getBody().get("startTime")).isNull();
    }

    // the day-start setting: step-up, range, and a conflict with an existing overnight session
    @Test
    void theDayStartCanBeChangedWithStepUpWithinRange() throws Exception {
        Clinic c = clinic("Asia/Dubai");
        assertThat(addHours(c, 2, "20:00:00", "03:00:00", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String url = "/v1/clinic/settings/day-start";
        assertThat(exchange(url, HttpMethod.PUT, authedJsonBody(c.token(), Map.of("dayStart", "05:00")), Map.class).getStatusCode())
                .as("no step-up").isEqualTo(HttpStatus.FORBIDDEN);
        for (String bad : List.of("06:00", "5:00", "24:00", "ab")) {
            assertThat(put(c, bad).getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<Map> conflict = put(c, "02:00"); // the 20:00-03:00 session would end after the new day start
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("code", "SESSIONS_CONFLICT");
        ResponseEntity<Map> ok = put(c, "05:30");
        assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("dayStart", "05:30");
        assertThat(jdbc.queryForObject("SELECT day_start_minute FROM tenants WHERE id = ?", Integer.class, c.tenant().id())).isEqualTo(330);
        // the new start is used at once: 05:00 local is still the previous clinic date
        clock.setLocal("2026-03-11T05:00:00", c.zone());
        assertThat(exchange("/v1/schedule/day", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("date", "2026-03-10");
        // audited
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'clinic.day_start_changed'", Integer.class))).isEqualTo(1);
    }

    private ResponseEntity<Map> put(Clinic c, String dayStart) throws Exception {
        Map<String, Object> claims = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(c.token().split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject((String) claims.get("sub")).claim("purpose", "step_up").claim("sid", claims.get("sid")).claim("tenantId", claims.get("tenantId"));
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        String stepUp = encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
        return exchange("/v1/clinic/settings/day-start", HttpMethod.PUT, authedJsonBodyWithHeader(c.token(), "X-Step-Up-Token", stepUp, Map.of("dayStart", dayStart)), Map.class);
    }

    // another clinic's day start is its own
    @Test
    void theDayStartIsPerTenant() {
        Clinic a = clinic("Asia/Dubai"), b = clinic("Asia/Dubai", 0);
        clock.setLocal("2026-03-11T00:30:00", "Asia/Dubai");
        assertThat(exchange("/v1/schedule/day", HttpMethod.GET, authed(a.token()), Map.class).getBody()).containsEntry("date", "2026-03-10");
        assertThat(exchange("/v1/schedule/day", HttpMethod.GET, authed(b.token()), Map.class).getBody()).containsEntry("date", "2026-03-11");
    }
}
