package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VitalsApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void moveTo(String token, String queueEntryId, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    @Test
    void recordingVitalsAtVitalsPendingAdvancesTheQueueToVitalsDone() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse3@a.com", "+919800010001", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V1", "+919999950001");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");

        ResponseEntity<Map> resp = exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "heightCm", 170.5, "weightKg", 68.2, "bpSystolic", 120, "bpDiastolic", 80,
                "pulseBpm", 72, "tempCelsius", 37.0, "spo2Percent", 98)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("bpSystolic")).intValue()).isEqualTo(120);

        ResponseEntity<java.util.List> list = exchange("/v1/queue?doctorId=" + staff.id(), HttpMethod.GET, authed(token), java.util.List.class);
        Map<?, ?> entry = (Map<?, ?>) list.getBody().stream()
                .filter(e -> queueEntryId.equals(((Map<?, ?>) e).get("id"))).findFirst().orElseThrow();
        assertThat(entry.get("status")).isEqualTo("vitals_done");
    }

    @Test
    void correctingVitalsAfterVitalsDoneDoesNotAttemptAnIllegalTransition() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse4@a.com", "+919800010002", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V2", "+919999950002");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending", "vitals_done");

        ResponseEntity<Map> resp = exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "heightCm", 171.0, "weightKg", 68.0, "bpSystolic", 118, "bpDiastolic", 78,
                "pulseBpm", 70, "tempCelsius", 36.9, "spo2Percent", 99)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("bpSystolic")).intValue()).isEqualTo(118);
    }

    @Test
    void roleWithoutClinicalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "reception3@a.com", "+919800010003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/clinical/vitals/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
