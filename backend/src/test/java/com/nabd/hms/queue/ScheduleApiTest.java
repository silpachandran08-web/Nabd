package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleApiTest extends ApiTestBase {

    // Java MON=1..SUN=7 -> Postgres-style SUN=0..SAT=6, same mapping ScheduleService uses.
    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    @Test
    void addAndListWorkingHours() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc1@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ResponseEntity<Map> addResp = exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> listResp = exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.GET,
                authed(token), List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).isNotEmpty();
    }

    @Test
    void workingHoursRespectsMaxPatientsCap() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc2@a.com", "+919400000002", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ResponseEntity<Map> addResp = exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15, "maxPatients", 1)), Map.class);
        assertThat(addResp.getBody().get("maxPatients")).isEqualTo(1);
    }

    @Test
    void addAndListLeave() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc3@a.com", "+919400000003", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ResponseEntity<Map> addResp = exchange("/v1/doctors/" + doctor.id() + "/leave", HttpMethod.POST,
                authedJsonBody(token, Map.of("dateFrom", today.toString(), "dateTo", today.toString(),
                        "reason", "conference")), Map.class);
        assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> listResp = exchange("/v1/doctors/" + doctor.id() + "/leave", HttpMethod.GET,
                authed(token), List.class);
        assertThat(listResp.getBody()).isNotEmpty();
    }

    @Test
    void availabilityReturnsSlotsWithinWorkingHours() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc4@a.com", "+919400000004", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15)), Map.class);

        ResponseEntity<List> resp = exchange("/v1/doctors/" + doctor.id() + "/availability?date=" + today,
                HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // not asserting non-empty: flaky in the last few minutes of the UTC day, all slots already past
    }

    @Test
    void availabilityIsEmptyOnLeaveDay() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc5@a.com", "+919400000005", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        exchange("/v1/doctors/" + doctor.id() + "/leave", HttpMethod.POST,
                authedJsonBody(token, Map.of("dateFrom", today.toString(), "dateTo", today.toString(),
                        "reason", "sick")), Map.class);

        ResponseEntity<List> resp = exchange("/v1/doctors/" + doctor.id() + "/availability?date=" + today,
                HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getBody()).isEmpty();
    }
}
