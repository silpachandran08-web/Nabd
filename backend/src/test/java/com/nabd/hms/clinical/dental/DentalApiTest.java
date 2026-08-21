package com.nabd.hms.clinical.dental;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DentalApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void chartingAToothIsReflectedInTheFullChart() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff dentist = seedStaff(tenant, roleId, "dentist1@a.com", "+919800040001", false);
        String token = loginAndGetAccessToken(dentist);
        String patientId = registerPatient(token, "D1", "+919999980001");

        ResponseEntity<Map> upsert = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/36", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "decayed", "note", "distal caries")), Map.class);
        assertThat(upsert.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upsert.getBody().get("toothNumber")).isEqualTo(36);
        assertThat(upsert.getBody().get("status")).isEqualTo("decayed");

        ResponseEntity<List> chart = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(token), List.class);
        assertThat(chart.getBody()).hasSize(1);

        // correcting the same tooth updates in place, not a second row — and a status-only PATCH
        // (no note in the body) must not wipe the note already on record.
        exchange("/v1/specialty/dental/patients/" + patientId + "/chart/36", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "filled")), Map.class);
        ResponseEntity<List> after = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(token), List.class);
        assertThat(after.getBody()).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) after.getBody().get(0);
        assertThat(row.get("status")).isEqualTo("filled");
        assertThat(row.get("note")).isEqualTo("distal caries");
    }

    @Test
    void outOfRangeToothNumberIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff dentist = seedStaff(tenant, roleId, "dentist2@a.com", "+919800040002", false);
        String token = loginAndGetAccessToken(dentist);
        String patientId = registerPatient(token, "D2", "+919999980002");

        ResponseEntity<Map> resp = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/99", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "decayed")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("invalid-tooth");
    }

    @Test
    void supernumeraryToothCoexistsWithAStandardToothAtTheSameNumberAndCanBeEditedAndRemoved() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff dentist = seedStaff(tenant, roleId, "dentist3@a.com", "+919800040004", false);
        String token = loginAndGetAccessToken(dentist);
        String patientId = registerPatient(token, "D3", "+919999980004");
        exchange("/v1/specialty/dental/patients/" + patientId + "/chart/11", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "healthy")), Map.class);

        ResponseEntity<Map> added = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/supernumerary", HttpMethod.POST,
                authedJsonBody(token, Map.of("nearToothNumber", 11, "status", "decayed", "note", "extra tooth near 11")), Map.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody().get("toothNumber")).isEqualTo(11);
        assertThat(added.getBody().get("isSupernumerary")).isEqualTo(true);
        String supernumeraryId = (String) added.getBody().get("id");

        ResponseEntity<List> chart = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(token), List.class);
        assertThat(chart.getBody()).hasSize(2); // the standard tooth 11 row plus this supernumerary one

        // status-only PATCH (no note in the body) must not wipe the note recorded at creation
        ResponseEntity<Map> updated = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/supernumerary/" + supernumeraryId,
                HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "filled")), Map.class);
        assertThat(updated.getBody().get("status")).isEqualTo("filled");
        assertThat(updated.getBody().get("note")).isEqualTo("extra tooth near 11");

        ResponseEntity<Void> removed = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/supernumerary/" + supernumeraryId,
                HttpMethod.DELETE, authed(token), Void.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<List> afterRemove = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(token), List.class);
        assertThat(afterRemove.getBody()).hasSize(1);
    }

    @Test
    void toothHistoryRecordsEveryChangeWithActorAndBeforeAfterState() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff dentist = seedStaff(tenant, roleId, "dentist4@a.com", "+919800040005", false);
        String token = loginAndGetAccessToken(dentist);
        String patientId = registerPatient(token, "D4", "+919999980005");

        ResponseEntity<Map> created = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/24", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "decayed")), Map.class);
        String toothId = (String) created.getBody().get("id");
        exchange("/v1/specialty/dental/patients/" + patientId + "/chart/24", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "filled")), Map.class);

        ResponseEntity<List> history = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/entries/" + toothId + "/history",
                HttpMethod.GET, authed(token), List.class);

        assertThat(history.getBody()).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) history.getBody().get(0);
        Map<?, ?> second = (Map<?, ?>) history.getBody().get(1);
        assertThat(first.get("action")).isEqualTo("dental_chart.create");
        assertThat(first.get("actorName")).isEqualTo("Test Staff");
        assertThat(first.get("before")).isNull();
        assertThat(first.get("after").toString()).contains("decayed");
        assertThat(second.get("action")).isEqualTo("dental_chart.update");
        assertThat(second.get("before").toString()).contains("decayed");
        assertThat(second.get("after").toString()).contains("filled");
    }

    @Test
    void roleWithoutSpecialtyDentalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "GeneralClinician", false, fullGrant("clinical"), fullGrant("patients"), fullGrant("queue"));
        SeededStaff staff = seedStaff(tenant, roleId, "generaldoc1@a.com", "+919800040003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/specialty/dental/patients/" + UUID.randomUUID() + "/chart", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
