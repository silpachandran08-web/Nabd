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

    @Test
    void emptyImplausibleOrInconsistentVitalsAreRejectedAndDoNotAdvanceTheVisit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse6@a.com", "+919800010006", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V6", "+919999950006");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        String url = "/v1/clinical/vitals/" + queueEntryId;

        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("tempCelsius", 370)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("bpSystolic", 80, "bpDiastolic", 90)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("bpSystolic", 120)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<java.util.List> list = exchange("/v1/queue?doctorId=" + staff.id(), HttpMethod.GET, authed(token), java.util.List.class);
        Map<?, ?> entry = (Map<?, ?>) list.getBody().stream()
                .filter(e -> queueEntryId.equals(((Map<?, ?>) e).get("id"))).findFirst().orElseThrow();
        assertThat(entry.get("status")).isEqualTo("vitals_pending");
    }

    // V2-008: a null / omitted field keeps the stored value; a partial save must not wipe the other measurements.
    @Test
    void aSecondSaveWithOnlyPulseKeepsTheOtherMeasurementsAndCorrectionsAreAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse-v28@a.com", "+919800010028", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V28", "+919999950028");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        String url = "/v1/clinical/vitals/" + queueEntryId;
        exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("heightCm", 170, "weightKg", 68, "bpSystolic", 120,
                "bpDiastolic", 80, "pulseBpm", 72, "tempCelsius", 37.0, "spo2Percent", 98)), Map.class);

        java.util.HashMap<String, Object> pulseOnly = new java.util.HashMap<>();
        pulseOnly.put("pulseBpm", 90);
        pulseOnly.put("heightCm", null);
        pulseOnly.put("bpSystolic", null);
        ResponseEntity<Map> resp = exchange(url, HttpMethod.PATCH, authedJsonBody(token, pulseOnly), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("pulseBpm")).intValue()).isEqualTo(90);
        assertThat(((Number) resp.getBody().get("bpSystolic")).intValue()).isEqualTo(120);
        assertThat(((Number) resp.getBody().get("heightCm")).doubleValue()).isEqualTo(170.0);
        assertThat(((Number) resp.getBody().get("spo2Percent")).intValue()).isEqualTo(98);

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT before, after FROM audit_log WHERE tenant_id = ? AND action = 'vitals.corrected' ORDER BY created_at DESC LIMIT 1",
                tenant.id()));
        assertThat(audited.get("before").toString()).contains("72");
        assertThat(audited.get("after").toString()).contains("90");
    }

    // V1-011: genuine neonatal / shock / hypothermic readings are accepted; impossible ones are still refused.
    @Test
    void neonatalAndCriticalReadingsAreAcceptedButImpossibleOnesAreNot() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse-v11@a.com", "+919800010011", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V11", "+919999950011");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        String url = "/v1/clinical/vitals/" + queueEntryId;

        ResponseEntity<Map> critical = exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "weightKg", 0.6, "heightCm", 32, "bpSystolic", 35, "bpDiastolic", 20, "spo2Percent", 42, "tempCelsius", 28.5)), Map.class);
        assertThat(critical.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> impossible = exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("spo2Percent", 0)), Map.class);
        assertThat(impossible.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(impossible.getBody().get("detail"))).contains("spo2Percent");
    }
}
