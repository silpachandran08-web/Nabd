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

    // ---- delegations (NB-057) ----

    @Test
    void delegatedRoleGrantsShowUpOnNextLoginAndDisappearAfterExpiry() {
        SeededTenant tenant = seedTenant();
        UUID ownerRoleId = seedFullAccessRole(tenant.id());
        UUID doctorRoleId = seedRole(tenant.id(), "Doctor Cover", false,
                new ModuleGrant("clinical", true, true, true, false, false, false, false));
        UUID nurseRoleId = seedRole(tenant.id(), "Nurse", false,
                new ModuleGrant("clinical", true, false, false, false, false, false, false));
        SeededStaff owner = seedStaff(tenant, ownerRoleId, "owner6@a.com", "+919200000007", false);
        SeededStaff nurse = seedStaff(tenant, nurseRoleId, "nurse6@a.com", "+919200000008", false);
        String ownerToken = loginAndGetAccessToken(owner);

        ResponseEntity<Map> createResp = exchange("/v1/roles/delegations", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "staffId", nurse.id().toString(), "delegatedRoleId", doctorRoleId.toString(),
                "reason", "Covering Dr. Shah's leave", "expiresAt", java.time.Instant.now().plusSeconds(3600).toString())),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String delegationId = (String) createResp.getBody().get("id");

        // the delegated grant is folded into the very next token this staff member mints
        String nurseToken = loginAndGetAccessToken(nurse);
        assertThat(permissionsOf(nurseToken)).contains("clinical:edit");

        // force it into the past and log in again — the grant is gone, and the expiry is audited
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE role_delegations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                UUID.fromString(delegationId)));
        String nurseTokenAfterExpiry = loginAndGetAccessToken(nurse);
        assertThat(permissionsOf(nurseTokenAfterExpiry)).doesNotContain("clinical:edit");

        Integer auditRows = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'role_delegation' AND entity_id = ? AND action = 'staff.delegation_expired'",
                Integer.class, UUID.fromString(delegationId)));
        assertThat(auditRows).isEqualTo(1);
    }

    @Test
    void delegatingBeyondGrantersOwnPermissionsIsBlocked() {
        SeededTenant tenant = seedTenant();
        UUID limitedRoleId = seedRole(tenant.id(), "Limited", true,
                new ModuleGrant("staff", true, true, true, false, false, false, false));
        UUID fullBillingRoleId = seedRole(tenant.id(), "Full Billing", false, fullGrant("billing"));
        SeededStaff granter = seedStaff(tenant, limitedRoleId, "limited2@a.com", "+919200000009", false);
        SeededStaff receiver = seedStaff(tenant, limitedRoleId, "receiver@a.com", "+919200000010", false);
        String token = loginAndGetAccessToken(granter);

        ResponseEntity<Map> resp = exchange("/v1/roles/delegations", HttpMethod.POST, authedJsonBody(token, Map.of(
                "staffId", receiver.id().toString(), "delegatedRoleId", fullBillingRoleId.toString(),
                "reason", "nice try", "expiresAt", java.time.Instant.now().plusSeconds(3600).toString())),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("privilege-escalation");
    }

    @Test
    void revokingADelegationEndsItImmediately() {
        SeededTenant tenant = seedTenant();
        UUID ownerRoleId = seedFullAccessRole(tenant.id());
        UUID doctorRoleId = seedRole(tenant.id(), "Doctor Cover 2", false,
                new ModuleGrant("clinical", true, true, true, false, false, false, false));
        UUID nurseRoleId = seedRole(tenant.id(), "Nurse 2", false,
                new ModuleGrant("clinical", true, false, false, false, false, false, false));
        SeededStaff owner = seedStaff(tenant, ownerRoleId, "owner7@a.com", "+919200000011", false);
        SeededStaff nurse = seedStaff(tenant, nurseRoleId, "nurse7@a.com", "+919200000012", false);
        String ownerToken = loginAndGetAccessToken(owner);

        ResponseEntity<Map> createResp = exchange("/v1/roles/delegations", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "staffId", nurse.id().toString(), "delegatedRoleId", doctorRoleId.toString(),
                "reason", "Covering", "expiresAt", java.time.Instant.now().plusSeconds(3600).toString())),
                Map.class);
        String delegationId = (String) createResp.getBody().get("id");

        ResponseEntity<Void> revokeResp = exchange("/v1/roles/delegations/" + delegationId, HttpMethod.DELETE, authed(ownerToken), Void.class);
        assertThat(revokeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String nurseToken = loginAndGetAccessToken(nurse);
        assertThat(permissionsOf(nurseToken)).doesNotContain("clinical:edit");
    }

    @SuppressWarnings("unchecked")
    private List<String> permissionsOf(String accessToken) {
        String[] parts = accessToken.split("\\.");
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
        try {
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            return (List<String>) claims.get("permissions");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
