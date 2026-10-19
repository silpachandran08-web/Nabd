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
        assertThat(getResp.getBody()).hasSize(10); // welcome..go_live, including "departments"

        ResponseEntity<Void> postResp = exchange("/v1/setup/checklist/profile/complete", HttpMethod.POST,
                authedJsonBody(token, Map.of("status", "done")), Void.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> updated = exchange("/v1/setup/checklist", HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> items = updated.getBody();
        assertThat(items).anyMatch(i -> "profile".equals(i.get("step")) && "done".equals(i.get("status")));
    }

    @Test
    void skipEndpointAlwaysSkipsRegardlessOfRequestBody() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        // A client claiming "done" in the body must not be able to mark a step done via .../skip.
        ResponseEntity<Void> resp = exchange("/v1/setup/checklist/tax/skip", HttpMethod.POST,
                authedJsonBody(token, Map.of("status", "done")), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> checklist = exchange("/v1/setup/checklist", HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> items = checklist.getBody();
        assertThat(items).anyMatch(i -> "tax".equals(i.get("step")) && "skipped".equals(i.get("status")));
    }

    @Test
    void completingGoLiveMarksSetupCompletedOnTheProfile() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> before = exchange("/v1/setup/profile", HttpMethod.GET, authed(token), Map.class);
        assertThat(before.getBody().get("setupCompletedAt")).isNull();

        ResponseEntity<Void> resp = exchange("/v1/setup/checklist/go_live/complete", HttpMethod.POST,
                authed(token), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> after = exchange("/v1/setup/profile", HttpMethod.GET, authed(token), Map.class);
        assertThat(after.getBody().get("setupCompletedAt")).isNotNull();
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
    void idleLockPolicyRejectsOutOfRange() { // FEF-005: 2-30 only; valid values need the policy_key CHECK widened (migration pending)
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);
        for (String bad : new String[]{"1", "31", "0", "abc", "5.5", "-3"}) {
            ResponseEntity<Map> r = exchange("/v1/setup/policies/session.idle_lock_minutes", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("value", bad)), Map.class);
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
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
    void subscriptionSummaryIsNotTheTenantsNameAndBrandlessTenantCountsAsOneBranch() {
        SeededTenant tenant = seedTenant(); // no brand_id — the common case (see V6)
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/setup/subscription", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // LIVE-07: no platform subscription yet -> no plan name (never a hard-coded label)
        assertThat(resp.getBody().get("plan")).isNull();
        assertThat(resp.getBody().get("branchesUsed")).isEqualTo(1);
    }

    @Test
    void subscriptionPlanIsThePlatformPlanRecordsName() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);
        UUID planId = seedPlan("mid-" + tenant.slug(), 300000, "INR", 10);
        jdbc.update("UPDATE master.plans SET name = ? WHERE id = ?", "Mid " + tenant.slug(), planId);
        jdbc.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) " +
                "VALUES (?, ?, 300000, 'INR', current_date + 30)", tenant.id(), planId);

        ResponseEntity<Map> resp = exchange("/v1/setup/subscription", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("plan")).isEqualTo("Mid " + tenant.slug());
    }

    @Test
    void branchesUsedCountsSiblingsSharingABrand() {
        SeededOwner owner = seedOwner("brand-owner@a.com");
        SeededBrand brand = seedBrand(owner, "Multi-Clinic Brand");
        // seedClinicInBrand already seeds its own full-access "Owner" role per tenant.
        SeededTenant clinicA = seedClinicInBrand(brand);
        seedClinicInBrand(brand); // sibling clinic under the same brand
        UUID roleId = inTenantTx(clinicA.id(), () -> jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id = ? AND name = 'Owner'", UUID.class, clinicA.id()));
        SeededStaff staff = seedStaff(clinicA, roleId, "owner@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/setup/subscription", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getBody().get("branchesUsed")).isEqualTo(2);
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
    void complianceHealthCountsLicencesAndIsPermissionGated() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@h.com", "+919400000071", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> empty = exchange("/v1/compliance/health", HttpMethod.GET, authed(token), Map.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) empty.getBody().get("licences")).get("total")).isEqualTo(0);
        assertThat(((Map) ((List) empty.getBody().get("items")).get(0)).get("status")).isEqualTo("attention");

        int n = 0;
        for (int days : new int[]{400, 10, -5}) {
            exchange("/v1/setup/licences", HttpMethod.POST, authedJsonBody(token, Map.of("licenceType", "facility",
                    "number", "L-" + n++, "expiryDate", LocalDate.now().plusDays(days).toString(), "region", "KSA")), Map.class);
        }
        ResponseEntity<Map> resp = exchange("/v1/compliance/health", HttpMethod.GET, authed(token), Map.class);
        Map lic = (Map) resp.getBody().get("licences");
        assertThat(lic.get("total")).isEqualTo(3);
        assertThat(lic.get("expiringSoon")).isEqualTo(1);
        assertThat(lic.get("expired")).isEqualTo(1);
        Map item = (Map) ((List) resp.getBody().get("items")).get(0);
        assertThat(item.get("kind")).isEqualTo("licence");
        assertThat(item.get("status")).isEqualTo("expired");
        assertThat(item.get("link")).isEqualTo("/setup?tab=licences");

        UUID noSetup = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"));
        SeededStaff other = seedStaff(tenant, noSetup, "q@h.com", "+919400000072", false);
        ResponseEntity<Map> denied = exchange("/v1/compliance/health", HttpMethod.GET, authed(loginAndGetAccessToken(other)), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
