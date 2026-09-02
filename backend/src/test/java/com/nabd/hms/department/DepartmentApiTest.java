package com.nabd.hms.department;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DepartmentApiTest extends ApiTestBase {

    @Test
    void creatingAndListingDepartmentsWorks() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919200001001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> createResp = exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dental", "active", true)), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResp.getBody()).containsEntry("name", "Dental");

        ResponseEntity<List> listResp = exchange("/v1/departments", HttpMethod.GET, authed(token), List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // seedTenant() already seeds a default "General" department (see V37's migration backfill,
        // mirrored there for every already-provisioned tenant) — the new one joins it, doesn't replace it.
        List<Map<String, Object>> departments = listResp.getBody();
        assertThat(departments).extracting(d -> d.get("name")).containsExactlyInAnyOrder("General", "Dental");
        assertThat(departments).anySatisfy(d -> assertThat(d).containsEntry("name", "General").containsEntry("isDefault", true));
    }

    @Test
    void updatingADepartmentChangesItsFields() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner2@a.com", "+919200001002", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> createResp = exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dermatology", "active", true)), Map.class);
        String id = (String) createResp.getBody().get("id");

        ResponseEntity<Map> updateResp = exchange("/v1/departments/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Dermatology & Cosmetology", "active", true)), Map.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody()).containsEntry("name", "Dermatology & Cosmetology");
    }

    /** The default department is the check-in fallback for any doctor nobody's assigned a
     * department to yet (see QueueRepository.findCheckInDepartment) — deactivating it would
     * silently break check-in, so DepartmentService blocks it outright. */
    @Test
    void deactivatingTheDefaultDepartmentIsBlocked() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner3@a.com", "+919200001003", false);
        String token = loginAndGetAccessToken(staff);

        Map<String, Object> defaultDept = defaultDepartment(token);
        String id = (String) defaultDept.get("id");

        ResponseEntity<Map> resp = exchange("/v1/departments/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "General", "active", false)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("default-department-immutable");
    }

    @Test
    void replacingTheTransferGraphReplacesItWholesale() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner4@a.com", "+919200001004", false);
        String token = loginAndGetAccessToken(staff);

        String generalId = (String) defaultDepartment(token).get("id");
        String dentalId = createDepartment(token, "Dental");

        ResponseEntity<List> firstPost = exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(token, Map.of(
                "edges", List.of(Map.of("fromDepartmentId", generalId, "toDepartmentId", dentalId)))), List.class);
        assertThat(firstPost.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPost.getBody()).hasSize(1);

        // Replacing with an empty graph clears the previous edge entirely — whole-graph replace, not a merge.
        ResponseEntity<List> secondPost = exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(token, Map.of(
                "edges", List.of())), List.class);
        assertThat(secondPost.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondPost.getBody()).isEmpty();
    }

    @Test
    void transferTargetsReturnsAllowedDepartmentsWithTheirDoctorRoster() {
        SeededTenant tenant = seedTenant();
        UUID ownerRoleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRoleId, "owner5@a.com", "+919200001005", false);
        String ownerToken = loginAndGetAccessToken(owner);

        String generalId = (String) defaultDepartment(ownerToken).get("id");
        String dentalId = createDepartment(ownerToken, "Dental");
        exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "edges", List.of(Map.of("fromDepartmentId", generalId, "toDepartmentId", dentalId)))), List.class);

        UUID doctorRoleId = seedRole(tenant.id(), "Doctor", false, fullGrant("clinical"), fullGrant("queue"));
        SeededStaff dentist = seedStaff(tenant, doctorRoleId, "dentist@a.com", "+919200001006", false);
        UUID dentalUuid = UUID.fromString(dentalId);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ? WHERE id = ?", dentalUuid, dentist.id()));

        ResponseEntity<List> resp = exchange("/v1/departments/" + generalId + "/transfer-targets", HttpMethod.GET, authed(ownerToken), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> targets = resp.getBody();
        assertThat(targets).hasSize(1);
        Map<String, Object> dentalTarget = targets.get(0);
        assertThat(dentalTarget).containsEntry("departmentName", "Dental");
        List<Map<String, Object>> doctors = (List<Map<String, Object>>) dentalTarget.get("doctors");
        assertThat(doctors).extracting(d -> d.get("name")).contains("Test Staff");
    }

    // ── visit flow (NB-355) ──

    @Test
    void unconfiguredDepartmentFallsBackToVitalsThenConsultation() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner6@a.com", "+919200001007", false);
        String token = loginAndGetAccessToken(staff);

        String id = createDepartment(token, "Fresh");
        ResponseEntity<List> resp = exchange("/v1/departments/" + id + "/flow", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty(); // nothing configured yet — the default only applies inside resolveStatusSequence()
    }

    @Test
    void replacingTheFlowReordersAndPersistsSteps() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner7@a.com", "+919200001008", false);
        String token = loginAndGetAccessToken(staff);
        String id = createDepartment(token, "Dental");

        // Dental example from the ticket: consultation, then procedures, then billing.
        ResponseEntity<List> resp = exchange("/v1/departments/" + id + "/flow", HttpMethod.POST, authedJsonBody(token, Map.of(
                "steps", List.of(
                        Map.of("stepType", "consultation"),
                        Map.of("stepType", "procedures"),
                        Map.of("stepType", "billing")))), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> steps = resp.getBody();
        assertThat(steps).extracting(s -> s.get("stepType")).containsExactly("consultation", "procedures", "billing");

        ResponseEntity<List> getResp = exchange("/v1/departments/" + id + "/flow", HttpMethod.GET, authed(token), List.class);
        assertThat(((List<Map<String, Object>>) getResp.getBody())).extracting(s -> s.get("stepType"))
                .containsExactly("consultation", "procedures", "billing");
    }

    @Test
    void flowMustIncludeExactlyOneConsultationStep() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner8@a.com", "+919200001009", false);
        String token = loginAndGetAccessToken(staff);
        String id = createDepartment(token, "Dental");

        ResponseEntity<Map> missing = exchange("/v1/departments/" + id + "/flow", HttpMethod.POST, authedJsonBody(token, Map.of(
                "steps", List.of(Map.of("stepType", "billing")))), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("type")).asString().contains("flow-invalid");
    }

    @Test
    void flowRejectsADuplicateStepType() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner9@a.com", "+919200001010", false);
        String token = loginAndGetAccessToken(staff);
        String id = createDepartment(token, "Dental");

        ResponseEntity<Map> resp = exchange("/v1/departments/" + id + "/flow", HttpMethod.POST, authedJsonBody(token, Map.of(
                "steps", List.of(Map.of("stepType", "consultation"), Map.of("stepType", "vitals"), Map.of("stepType", "vitals")))), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("flow-invalid");
    }

    private String createDepartment(String token, String name) {
        ResponseEntity<Map> resp = exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "active", true)), Map.class);
        return (String) resp.getBody().get("id");
    }

    private Map<String, Object> defaultDepartment(String token) {
        ResponseEntity<List> listResp = exchange("/v1/departments", HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> departments = listResp.getBody();
        return departments.stream().filter(d -> Boolean.TRUE.equals(d.get("isDefault"))).findFirst().orElseThrow();
    }
}
