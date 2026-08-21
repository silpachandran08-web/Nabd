package com.nabd.hms.setup;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SetupApiTest extends ApiTestBase {

    @Test
    void checklistSeededAndCanBeMarkedDone() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<List> getResp = exchange("/v1/setup/checklist", HttpMethod.GET, authed(token), List.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody()).hasSize(9);

        ResponseEntity<Void> postResp = exchange("/v1/setup/checklist/profile/complete", HttpMethod.POST,
                authedJsonBody(token, Map.of("status", "done")), Void.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> updated = exchange("/v1/setup/checklist", HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> items = updated.getBody();
        assertThat(items).anyMatch(i -> "profile".equals(i.get("step")) && "done".equals(i.get("status")));
    }

    @Test
    void profileCrud() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> getResp = exchange("/v1/setup/profile", HttpMethod.GET, authed(token), Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("name")).isEqualTo("Test Clinic " + tenant.slug());

        ResponseEntity<Map> patchResp = exchange("/v1/setup/profile", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("name", "Updated Clinic", "timezone", "Asia/Kolkata", "taxIdType", "GSTIN", "taxId", "22AAAAA0000A1Z5", "specialties", List.of("Dental"))),
                Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResp.getBody().get("name")).isEqualTo("Updated Clinic");
        assertThat(patchResp.getBody().get("taxIdType")).isEqualTo("GSTIN");
    }

    @Test
    void chargeHeadCrud() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> postResp = exchange("/v1/setup/charges", HttpMethod.POST,
                authedJsonBody(token, Map.of("code", "CONS", "name", "Consultation", "category", "Consultation", "baseAmount", 500, "active", true, "effectiveFrom", LocalDate.now().toString())),
                Map.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) postResp.getBody().get("id");
        assertThat(postResp.getBody().get("active")).isEqualTo(true);

        ResponseEntity<Map> patchResp = exchange("/v1/setup/charges/" + id, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("code", "CONS", "name", "Consultation", "category", "Consultation", "baseAmount", 600, "active", false, "effectiveFrom", LocalDate.now().toString())),
                Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) patchResp.getBody().get("baseAmount")).intValue()).isEqualTo(600);
        assertThat(patchResp.getBody().get("active")).isEqualTo(false);
    }

    @Test
    void policiesSeededAndUpdated() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<List> getResp = exchange("/v1/setup/policies", HttpMethod.GET, authed(token), List.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> policies = getResp.getBody();
        assertThat(policies).anyMatch(p -> "cancellation_window_hours".equals(p.get("policyKey")));

        ResponseEntity<Map> patchResp = exchange("/v1/setup/policies/cancellation_window_hours", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("value", "4")), Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResp.getBody().get("value")).isEqualTo("4");
        assertThat(patchResp.getBody().get("version")).isEqualTo(2);
    }

    @Test
    void consentContactCrud() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> patchResp = exchange("/v1/setup/consent-contact", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("name", "Nandini Rao", "email", "compliance@clinic.example", "phone", "+919900000000")),
                Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResp.getBody().get("email")).isEqualTo("compliance@clinic.example");

        ResponseEntity<Map> getResp = exchange("/v1/setup/consent-contact", HttpMethod.GET, authed(token), Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("name")).isEqualTo("Nandini Rao");
    }

    @Test
    void holidayCrud() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        LocalDate date = LocalDate.now().plusMonths(1);
        ResponseEntity<Map> postResp = exchange("/v1/setup/holidays", HttpMethod.POST,
                authedJsonBody(token, Map.of("holidayDate", date.toString(), "name", "Test Holiday", "recurring", false)),
                Map.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) postResp.getBody().get("id");

        ResponseEntity<List> getResp = exchange("/v1/setup/holidays", HttpMethod.GET, authed(token), List.class);
        assertThat(getResp.getBody()).hasSize(1);

        ResponseEntity<Void> delResp = exchange("/v1/setup/holidays/" + id, HttpMethod.DELETE, authed(token), Void.class);
        assertThat(delResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> afterDel = exchange("/v1/setup/holidays", HttpMethod.GET, authed(token), List.class);
        assertThat(afterDel.getBody()).isEmpty();
    }

    @Test
    void shiftCrud() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staffMember = seedStaff(tenant, roleId, "reception@a.com", "+919400000002", false);
        SeededStaff owner = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Map> postResp = exchange("/v1/setup/shifts", HttpMethod.POST,
                authedJsonBody(token, Map.of("staffId", staffMember.id().toString(), "patternJson", "{}", "effectiveFrom", LocalDate.now().toString())),
                Map.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> getResp = exchange("/v1/setup/shifts", HttpMethod.GET, authed(token), List.class);
        assertThat(getResp.getBody()).hasSize(1);
    }

    @Test
    void subscriptionSummary() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/setup/subscription", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("plan")).isEqualTo("Test Clinic " + tenant.slug());
    }

    @Test
    void importExportJobs() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> importResp = exchange("/v1/setup/import-jobs", HttpMethod.POST,
                authedJsonBody(token, Map.of("importType", "patients", "fileName", "patients.csv")), Map.class);
        assertThat(importResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(importResp.getBody().get("status")).isEqualTo("pending");

        ResponseEntity<Map> exportResp = exchange("/v1/setup/export-jobs", HttpMethod.POST,
                authedJsonBody(token, Map.of("exportType", "full_tenant")), Map.class);
        assertThat(exportResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exportResp.getBody().get("exportType")).isEqualTo("full_tenant");
    }

    @Test
    void licenceCrud() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        LocalDate expiry = LocalDate.now().plusYears(1);
        ResponseEntity<Map> postResp = exchange("/v1/setup/licences", HttpMethod.POST,
                authedJsonBody(token, Map.of("licenceType", "facility", "number", "MOH-FL-123", "issuingBody", "MOH", "expiryDate", expiry.toString(), "region", "KSA")),
                Map.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) postResp.getBody().get("id");
        assertThat(postResp.getBody().get("status")).isEqualTo("valid");

        ResponseEntity<Map> patchResp = exchange("/v1/setup/licences/" + id, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("licenceType", "facility", "number", "MOH-FL-124", "expiryDate", expiry.toString(), "region", "KSA")),
                Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResp.getBody().get("number")).isEqualTo("MOH-FL-124");
    }

    @Test
    void payrollExportReturnsRows() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staffMember = seedStaff(tenant, roleId, "reception@a.com", "+919400000002", false);
        SeededStaff owner = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(owner);

        String month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        ResponseEntity<List> resp = exchange("/v1/setup/payroll-export?month=" + month, HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
    }

    @Test
    void nonOwnerRoleDenied() {
        SeededTenant tenant = seedTenant();
        UUID limitedRole = seedRole(tenant.id(), "Reception", true,
                new com.nabd.hms.common.ModuleGrant("patients", true, true, true, false, false, false, false));
        SeededStaff staff = seedStaff(tenant, limitedRole, "reception@a.com", "+919400000002", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<List> resp = exchange("/v1/setup/checklist", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
