package com.nabd.hms.staff;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoleApiTest extends ApiTestBase {

    @Test
    void listReturnsSeededRole() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919200000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<List> resp = exchange("/v1/roles", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void createRoleWithinCallerPermissionsSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner2@a.com", "+919200000002", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/roles", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Receptionist",
                "grants", List.of(Map.of("module", "queue", "view", true, "create", true, "edit", false,
                        "delete", false, "approve", false, "refundDiscount", false, "export", false)))),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("name")).isEqualTo("Receptionist");
    }

    @Test
    void createRoleExceedingCallerPermissionsIsBlocked() {
        SeededTenant tenant = seedTenant();
        // caller's own role only grants queue:view — no staff:* or patients:* permissions at all
        UUID limitedRoleId = seedRole(tenant.id(), "Front Desk", true,
                new ModuleGrant("queue", true, false, false, false, false, false, false),
                new ModuleGrant("staff", true, true, false, false, false, false, false)); // staff:create so they CAN call this endpoint
        SeededStaff staff = seedStaff(tenant, limitedRoleId, "limited@a.com", "+919200000003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/roles", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Sneaky",
                "grants", List.of(Map.of("module", "patients", "view", true, "create", true, "edit", true,
                        "delete", true, "approve", false, "refundDiscount", false, "export", false)))),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("privilege-escalation");
    }

    @Test
    void updateBuiltInRoleIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id()); // built_in = true
        SeededStaff staff = seedStaff(tenant, roleId, "owner3@a.com", "+919200000004", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/roles/" + roleId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Owner Renamed",
                "grants", List.of(Map.of("module", "staff", "view", true, "create", false, "edit", false,
                        "delete", false, "approve", false, "refundDiscount", false, "export", false)))),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("built-in-role-immutable");
    }

    @Test
    void updateCustomRoleSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID ownerRoleId = seedFullAccessRole(tenant.id());
        UUID customRoleId = seedRole(tenant.id(), "Custom", false, fullGrant("queue"));
        SeededStaff staff = seedStaff(tenant, ownerRoleId, "owner4@a.com", "+919200000005", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/roles/" + customRoleId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Custom Renamed",
                "grants", List.of(Map.of("module", "queue", "view", true, "create", false, "edit", false,
                        "delete", false, "approve", false, "refundDiscount", false, "export", false)))),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("name")).isEqualTo("Custom Renamed");
    }

    @Test
    void getReturns404ForUnknownRole() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner5@a.com", "+919200000006", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/roles/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
