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

        // correcting the same tooth updates in place, not a second row
        exchange("/v1/specialty/dental/patients/" + patientId + "/chart/36", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "filled")), Map.class);
        ResponseEntity<List> after = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(token), List.class);
        assertThat(after.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) after.getBody().get(0)).get("status")).isEqualTo("filled");
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
    void roleWithoutSpecialtyDentalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "GeneralClinician", false, fullGrant("clinical"), fullGrant("patients"), fullGrant("queue"));
        SeededStaff staff = seedStaff(tenant, roleId, "generaldoc1@a.com", "+919800040003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/specialty/dental/patients/" + UUID.randomUUID() + "/chart", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
