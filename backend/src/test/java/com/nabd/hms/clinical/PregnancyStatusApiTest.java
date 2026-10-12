package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-021. NOT EXECUTED in the authoring environment (no Docker / Testcontainers). */
class PregnancyStatusApiTest extends ApiTestBase {

    private String registerPatient(String token, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "P1", "phone", phone, "dob", "1995-01-01", "gender", "female")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void anUnrecordedPatientIsNotDocumentedNeverNotPregnant() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "preg1@a.com", "+919800000051", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "+919999950001");

        ResponseEntity<Map> resp = exchange("/v1/clinical/patients/" + patientId + "/pregnancy-status", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("not_documented");
        assertThat(resp.getBody().get("recordedAt")).isNull();
    }

    @Test
    void recordingStampsWhoAndWhenKeepsHistoryAndRejectsInvalidInput() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "preg2@a.com", "+919800000052", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "+919999950002");
        String url = "/v1/clinical/patients/" + patientId + "/pregnancy-status";

        ResponseEntity<Map> put = exchange(url, HttpMethod.PUT, authedJsonBody(token, Map.of("status", "pregnant", "expectedDeliveryDate", "2027-03-01")), Map.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().get("status")).isEqualTo("pregnant");
        assertThat(put.getBody().get("recordedBy")).isNotNull();
        assertThat(put.getBody().get("recordedAt")).isNotNull();

        assertThat(exchange(url, HttpMethod.PUT, authedJsonBody(token, Map.of("status", "not_documented")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PUT, authedJsonBody(token, Map.of("status", "lactating", "expectedDeliveryDate", "2027-03-01")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        exchange(url, HttpMethod.PUT, authedJsonBody(token, Map.of("status", "lactating")), Map.class);
        ResponseEntity<List> history = exchange(url + "/history", HttpMethod.GET, authed(token), List.class);
        assertThat(history.getBody()).hasSize(2);
    }

    @Test
    void readNeedsClinicalViewAndWriteNeedsClinicalEdit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "preg3@a.com", "+919800000053", false);
        String token = loginAndGetAccessToken(staff);
        String url = "/v1/clinical/patients/" + UUID.randomUUID() + "/pregnancy-status";

        assertThat(exchange(url, HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(url, HttpMethod.PUT, authedJsonBody(token, Map.of("status", "pregnant")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
