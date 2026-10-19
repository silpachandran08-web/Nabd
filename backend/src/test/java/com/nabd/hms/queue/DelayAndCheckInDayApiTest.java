package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-001: check-in refuses an appointment not dated today (clinic zone); delays are bounded and lapse with the clinic day. */
class DelayAndCheckInDayApiTest extends ApiTestBase {

    private record Ctx(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private Ctx ctx(String tz) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", tz, tenant.id());
        String n = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "d" + n + "@a.com",
                "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        return new Ctx(tenant, doctor, loginAndGetAccessToken(doctor));
    }

    private String patient(Ctx c, String name) {
        int n = (int) (Math.random() * 899999) + 100000;
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of("name", name,
                "phone", "+91977" + n + "0", "dob", (1950 + n % 40) + "-0" + (1 + n % 9) + "-1" + (n % 9), "gender", "male")),
                Map.class).getBody().get("id");
    }

    private void hoursEveryDay(Ctx c) {
        for (int d = 0; d < 7; d++) {
            exchange("/v1/doctors/" + c.doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                    "dayOfWeek", d, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
    }

    private ResponseEntity<Map> book(Ctx c, String patientId, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of("doctorId", c.doctor.id().toString(),
                "patientId", patientId, "startTime", start.toString())), Map.class);
    }

    private ResponseEntity<Map> checkIn(Ctx c, String patientId, Object appointmentId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of("patientId", patientId,
                "doctorId", c.doctor.id().toString(), "appointmentId", appointmentId)), Map.class);
    }

    private ResponseEntity<Map> announce(Ctx c, Object minutes) {
        return exchange("/v1/doctors/" + c.doctor.id() + "/delay", HttpMethod.POST,
                authedJsonBody(c.token, Map.of("delayMinutes", minutes, "reason", "r")), Map.class);
    }

    private int delayRows(Ctx c) {
        return inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM doctor_delays", Integer.class));
    }

    @Test
    void delayAbove240IsRejectedWithFieldErrorAndNoRow() {
        Ctx c = ctx("Asia/Kolkata");
        ResponseEntity<Map> r = announce(c, 241);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("type")).asString().contains("validation-failed");
        assertThat(((List<Map<String, String>>) r.getBody().get("errors"))).anyMatch(e -> "delayMinutes".equals(e.get("field")));
        assertThat(delayRows(c)).isZero();
        assertThat(announce(c, 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(announce(c, 240).getStatusCode()).isEqualTo(HttpStatus.CREATED); // boundary
        assertThat(delayRows(c)).isEqualTo(1);
    }

    @Test
    void announcedDelayCarriesClinicDateAndAuditsAnnounceAndClear() {
        Ctx c = ctx("Asia/Kolkata");
        ResponseEntity<Map> r = announce(c, 30);
        assertThat(r.getBody().get("clinicDate")).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
        assertThat(exchange("/v1/doctors/" + c.doctor.id() + "/delay/clear", HttpMethod.POST, authed(c.token), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<String> actions = inTenantTx(c.tenant.id(), () -> jdbc.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'doctor_delay' ORDER BY created_at", String.class));
        assertThat(actions).containsExactly("doctor_delay.announced", "doctor_delay.cleared");
    }

    @Test
    void yesterdaysUnclearedDelayIsLapsedToday() {
        Ctx c = ctx("Asia/Kolkata");
        announce(c, 45);
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
        inTenantTx(c.tenant.id(), () -> jdbc.update("UPDATE doctor_delays SET clinic_date = ?", java.sql.Date.valueOf(yesterday)));

        List<Map<String, Object>> history = exchange("/v1/doctors/" + c.doctor.id() + "/delay", HttpMethod.GET,
                authed(c.token), List.class).getBody();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("active")).isEqualTo(false);
        assertThat(history.get(0).get("clearedAt")).isNull();
        assertThat(history.get(0).get("clinicDate")).isEqualTo(yesterday.toString());
        // nothing active to clear
        assertThat(exchange("/v1/doctors/" + c.doctor.id() + "/delay/clear", HttpMethod.POST, authed(c.token), Map.class)
                .getBody().get("type")).asString().contains("no-active-delay");
        // a fresh announcement today is accepted and is the only active one
        assertThat(announce(c, 10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void losingTheRaceOnTheActiveIndexIsA409NotA500() throws Exception {
        Ctx c = ctx("Asia/Kolkata");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        try (Connection rival = superuserConnection()) {
            rival.setAutoCommit(false);
            try (PreparedStatement ps = rival.prepareStatement(
                    "INSERT INTO doctor_delays (tenant_id, doctor_id, delay_minutes, announced_by, clinic_date) VALUES (?,?,?,?,?)")) {
                ps.setObject(1, c.tenant.id());
                ps.setObject(2, c.doctor.id());
                ps.setInt(3, 15);
                ps.setObject(4, c.doctor.id());
                ps.setObject(5, today);
                ps.executeUpdate(); // uncommitted: invisible to the API's clear step, but holds the unique key
            }
            CompletableFuture<ResponseEntity<Map>> api = CompletableFuture.supplyAsync(() -> announce(c, 20));
            Thread.sleep(1500);
            assertThat(api.isDone()).as("API call blocks on the rival's uncommitted row").isFalse();
            rival.commit();
            ResponseEntity<Map> r = api.get();
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("type")).asString().contains("delay-already-active");
        }
    }

    @Test
    void twoSimultaneousAnnouncementsNeverReturn500() throws Exception {
        Ctx c = ctx("Asia/Kolkata");
        var a = CompletableFuture.supplyAsync(() -> announce(c, 20).getStatusCode().value());
        var b = CompletableFuture.supplyAsync(() -> announce(c, 25).getStatusCode().value());
        Set<Integer> codes = new HashSet<>(List.of(a.get(), b.get()));
        assertThat(codes).isSubsetOf(201, 409);
        assertThat(codes).contains(201);
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM doctor_delays WHERE cleared_at IS NULL", Integer.class))).isEqualTo(1);
    }

    @Test
    void checkInOfTomorrowsAppointmentIs409AndCreatesNoQueueEntry() {
        Ctx c = ctx("Asia/Kolkata");
        hoursEveryDay(c);
        String p = patient(c, "Tomorrow Pat");
        Instant start = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).atTime(12, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        ResponseEntity<Map> appt = book(c, p, start);
        assertThat(appt.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> r = checkIn(c, p, appt.getBody().get("id"));
        assertThat(r.getStatusCode()).as("appt=" + appt.getBody() + " resp=" + r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("type")).asString().contains("appointment-not-today");
        assertThat(r.getBody().get("detail")).asString().contains(start.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString());
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries", Integer.class))).isZero();
    }

    /** The clinic date, not the UTC date, decides. Pago Pago (UTC-11) and Kiritimati (UTC+14) between them
     * always sit on a different calendar date from UTC; the same shape as Dubai at 23:50 local. */
    @Test
    void checkInUsesClinicDateNotUtcDate() {
        String tz = LocalDate.now(ZoneId.of("Pacific/Kiritimati")).equals(LocalDate.now(ZoneId.of("UTC")))
                ? "Pacific/Pago_Pago" : "Pacific/Kiritimati";
        Ctx c = ctx(tz);
        assertThat(LocalDate.now(ZoneId.of(tz))).isNotEqualTo(LocalDate.now(ZoneId.of("UTC")));
        hoursEveryDay(c);
        String p = patient(c, "Zone Pat");
        ResponseEntity<Map> appt = book(c, p, nextQuarterHour());
        assertThat(appt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(checkIn(c, p, appt.getBody().get("id")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void checkInOfTodaysAppointmentStillWorks() {
        Ctx c = ctx("Asia/Dubai");
        hoursEveryDay(c);
        String p = patient(c, "Today Pat");
        ResponseEntity<Map> appt = book(c, p, nextQuarterHour());
        assertThat(checkIn(c, p, appt.getBody().get("id")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** ACC-004: bookings must sit on the 15-minute grid (hours start 00:00; every tz used here is on a quarter-hour offset). */
    private static Instant nextQuarterHour() {
        return Instant.ofEpochSecond((Instant.now().getEpochSecond() + 60 + 899) / 900 * 900);
    }
}
