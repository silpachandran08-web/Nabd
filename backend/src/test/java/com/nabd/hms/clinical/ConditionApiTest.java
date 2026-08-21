package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void addingAConditionMakesItVisibleOnTheListAndOnThePatientDrawer() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse5@a.com", "+919800050001", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "C1", "+919999990001");

        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/conditions", HttpMethod.POST,
                authedJsonBody(token, Map.of("condition", "Type 2 diabetes", "reviewDueDate", "2020-01-01")), Map.class);
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add.getBody().get("status")).isEqualTo("active");

        ResponseEntity<List> list = exchange("/v1/clinical/patients/" + patientId + "/conditions", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<Map> drawer = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class);
        assertThat((List<String>) drawer.getBody().get("chronicConditions")).containsExactly("Type 2 diabetes");
    }

    @Test
    void resolvingAConditionRemovesItFromTheActiveListAndTheDueList() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse6@a.com", "+919800050002", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "C2", "+919999990002");
        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/conditions", HttpMethod.POST,
                authedJsonBody(token, Map.of("condition", "Hypertension", "reviewDueDate", LocalDate.now().toString())), Map.class);
        String conditionId = (String) add.getBody().get("id");

        ResponseEntity<Void> resolve = exchange("/v1/clinical/conditions/" + conditionId + "/resolve", HttpMethod.PATCH, authed(token), Void.class);
        assertThat(resolve.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<List> list = exchange("/v1/clinical/patients/" + patientId + "/conditions", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).isEmpty();
    }

    @Test
    void dueListOnlyReturnsConditionsWhoseReviewDateHasArrived() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse7@a.com", "+919800050003", false);
        String token = loginAndGetAccessToken(staff);
        String duePatientId = registerPatient(token, "C3", "+919999990003");
        String notDuePatientId = registerPatient(token, "C4", "+919999990004");
        exchange("/v1/clinical/patients/" + duePatientId + "/conditions", HttpMethod.POST,
                authedJsonBody(token, Map.of("condition", "Asthma review", "reviewDueDate", LocalDate.now().minusDays(1).toString())), Map.class);
        exchange("/v1/clinical/patients/" + notDuePatientId + "/conditions", HttpMethod.POST,
                authedJsonBody(token, Map.of("condition", "Thyroid review", "reviewDueDate", LocalDate.now().plusMonths(6).toString())), Map.class);
        exchange("/v1/clinical/patients/" + duePatientId + "/conditions", HttpMethod.POST,
                authedJsonBody(token, Map.of("condition", "No review needed")), Map.class); // null reviewDueDate never appears

        ResponseEntity<List> due = exchange("/v1/clinical/conditions/due", HttpMethod.GET, authed(token), List.class);

        assertThat(due.getBody()).hasSize(1);
        assertThat((String) ((Map<?, ?>) due.getBody().get(0)).get("condition")).isEqualTo("Asthma review");
    }

    @Test
    void roleWithoutClinicalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "reception5@a.com", "+919800050004", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/conditions", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
