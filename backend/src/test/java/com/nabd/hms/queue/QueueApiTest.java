package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueueApiTest extends ApiTestBase {

    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    private void addWorkingHours(String token, UUID doctorId, Integer maxPatients) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("dayOfWeek", pgDayOfWeek(LocalDate.now(ZoneOffset.UTC)),
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15));
        if (maxPatients != null) {
            body.put("maxPatients", maxPatients);
        }
        exchange("/v1/doctors/" + doctorId + "/working-hours", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void walkInCheckInAssignsToken() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919600000001", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q1", "+919999910001");

        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("checked_in");
        assertThat(((Number) resp.getBody().get("tokenNumber")).intValue()).isGreaterThan(0);
        assertThat(resp.getBody().get("createdAt")).isNotNull(); // NB-095: arrivals board needs this for the "Wait" column
    }

    @Test
    void walkInCheckInBeyondCapacityIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner2@a.com", "+919600000002", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), 1);
        String patientA = registerPatient(token, "Q2", "+919999910002");
        String patientB = registerPatient(token, "Q3", "+919999910003");

        ResponseEntity<Map> first = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientA, "doctorId", staff.id().toString())), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientB, "doctorId", staff.id().toString())), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("type")).asString().contains("session-full");
    }

    @Test
    void checkInWithMismatchedAppointmentFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner3@a.com", "+919600000003", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Q4", "+919999910004");

        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "appointmentId", UUID.randomUUID().toString(), "patientId", patientId, "doctorId", staff.id().toString())),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsCheckedInEntries() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner4@a.com", "+919600000004", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q5", "+919999910005");
        exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);

        ResponseEntity<java.util.List> resp = exchange("/v1/queue?doctorId=" + staff.id(), HttpMethod.GET, authed(token),
                java.util.List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void legalTransitionSucceedsAndIllegalTransitionIsBlocked() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner5@a.com", "+919600000005", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q6", "+919999910006");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");

        ResponseEntity<Map> illegal = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "completed")), Map.class);
        assertThat(illegal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(illegal.getBody().get("type")).asString().contains("illegal-transition");

        ResponseEntity<Map> legal = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "waiting")), Map.class);
        assertThat(legal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(legal.getBody().get("status")).isEqualTo("waiting");
    }

    @Test
    void reorderSetsPriority() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner6@a.com", "+919600000006", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q7", "+919999910007");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");

        ResponseEntity<Map> resp = exchange("/v1/queue/" + entryId + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of(
                "priority", true, "reason", "elderly patient")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("priority")).isEqualTo(true);
        assertThat(resp.getBody().get("priorityReason")).isEqualTo("elderly patient");
    }
}
