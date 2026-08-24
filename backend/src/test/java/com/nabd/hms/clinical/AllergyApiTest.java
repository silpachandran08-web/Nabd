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

class AllergyApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void addingAnAllergyMakesItVisibleOnTheRegisterAndOnThePatientDrawer() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse1@a.com", "+919800000001", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "A1", "+919999940001");

        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add.getBody().get("active")).isEqualTo(true);

        ResponseEntity<List> list = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<Map> drawer = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class);
        assertThat((List<String>) drawer.getBody().get("allergies")).containsExactly("Penicillin");
    }

    @Test
    void deactivatingAnAllergyRemovesItFromTheActiveList() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse2@a.com", "+919800000002", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "A2", "+919999940002");
        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Sulfa", "severity", "mild", "reaction", "rash")), Map.class);
        String allergyId = (String) add.getBody().get("id");

        ResponseEntity<Void> deactivate = exchange("/v1/clinical/allergies/" + allergyId + "/deactivate", HttpMethod.PATCH,
                authed(token), Void.class);
        assertThat(deactivate.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<List> list = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).isEmpty();
    }

    @Test
    void roleWithoutClinicalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "reception2@a.com", "+919800000003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/allergies", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
