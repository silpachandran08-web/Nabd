package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Working hours are the clinic's wall clock. Regression for the bug where "09:00–13:00" was read as
 * UTC: a 10:00 India-time booking (04:30Z) matched no session, so the session cap was never applied.
 */
class ClinicTimezoneApiTest extends ApiTestBase {

    @Test
    void sessionCapAppliesInTheClinicsOwnTimezone() {
        for (String tz : List.of("Asia/Kolkata", "Asia/Riyadh")) {
            Clinic c = clinic(tz, 1);
            assertThat(book(c, "10:00").getStatusCode()).as(tz).isEqualTo(HttpStatus.CREATED);
            assertThat(book(c, "10:15").getStatusCode()).as(tz + " — second patient in a 1-patient session")
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void slotsStartAtTheClinicsNineOClock() {
        Clinic c = clinic("Asia/Kolkata", null);
        List<?> slots = exchange("/v1/doctors/" + c.doctor + "/availability?date=" + c.day, HttpMethod.GET,
                authed(c.token), List.class).getBody();
        assertThat(Instant.parse((String) slots.get(0)).atZone(c.zone).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void profileAcceptsOnlyRealTimezones() {
        SeededTenant tenant = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), "tz@a.com", "+919500000990", false));
        for (String bad : List.of("IST", "+05:30", "Asia/Nowhere", "")) {
            assertThat(exchange("/v1/setup/profile", HttpMethod.PATCH, authedJsonBody(token, Map.of("name", "Clinic", "timezone", bad)),
                    Map.class).getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<Map> ok = exchange("/v1/setup/profile", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("name", "Clinic", "timezone", "Asia/Kolkata")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("timezone")).isEqualTo("Asia/Kolkata");
    }

    private record Clinic(String token, UUID doctor, LocalDate day, ZoneId zone) {
    }

    private Clinic clinic(String tz, Integer maxPatients) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", tz, tenant.id());
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()),
                "d" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 8999999)), false);
        String token = loginAndGetAccessToken(doctor);
        ZoneId zone = ZoneId.of(tz);
        LocalDate day = LocalDate.now(zone).plusDays(2);
        Map<String, Object> hours = new java.util.HashMap<>(Map.of("dayOfWeek", day.getDayOfWeek().getValue() % 7,
                "startTime", "09:00:00", "endTime", "13:00:00", "slotMinutes", 15));
        if (maxPatients != null) {
            hours.put("maxPatients", maxPatients);
        }
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, hours), Map.class);
        return new Clinic(token, doctor.id(), day, zone);
    }

    /** Exactly what consult/page.tsx sends: a wall-clock time in the clinic's zone, as a UTC instant. */
    private ResponseEntity<Map> book(Clinic c, String localTime) {
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                // distinct names and DOBs so duplicate detection (NB-060) doesn't merge the two bookings' patients
                "name", localTime.equals("10:00") ? "Asha Menon" : "Ravi Kumar", "phone", "+9199" + (10000000 + (int) (Math.random() * 8999999)),
                "dob", localTime.equals("10:00") ? "1990-01-01" : "1978-06-15", "gender", "male")), Map.class);
        String patient = (String) reg.getBody().get("id");
        String start = c.day.atTime(LocalTime.parse(localTime)).atZone(c.zone).toInstant().toString();
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "doctorId", c.doctor.toString(), "patientId", patient, "startTime", start)), Map.class);
    }
}
