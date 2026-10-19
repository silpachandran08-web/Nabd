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

/** PLT-015: patch and invite can never hand out more than the caller holds; changes are audited and kill sessions. */
class RoleAssignmentEscalationTest extends ApiTestBase {

    private static final ModuleGrant BILLING_VIEW = new ModuleGrant("billing", true, false, false, false, false, false, false);
    private static final ModuleGrant BILLING_REFUND = new ModuleGrant("billing", true, false, false, false, false, true, false);

    private ResponseEntity<Map> patch(String token, UUID target, Map<String, Object> body) {
        return exchange("/v1/staff/" + target, HttpMethod.PATCH, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> invite(String token, UUID roleId, String email, String mobile) {
        return exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", email, "name", "Invitee", "mobilePhone", mobile, "roleId", roleId.toString())), Map.class);
    }

    private SeededStaff manager(SeededTenant t, String email, String mobile) {
        return seedStaff(t, seedRole(t.id(), "Mgr" + UUID.randomUUID().toString().substring(0, 6), false,
                fullGrant("staff"), BILLING_VIEW), email, mobile, false);
    }

    private String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    @Test
    void patchToRoleWithRefundDiscountIsRefusedForCallerWithoutIt() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = manager(t, "m1@a.com", "+919500000001");
        SeededStaff target = manager(t, "t1@a.com", "+919500000002");
        UUID rich = seedRole(t.id(), "Rich", false, fullGrant("staff"), BILLING_REFUND);

        ResponseEntity<Map> resp = patch(loginAndGetAccessToken(mgr), target.id(), Map.of("roleId", rich.toString()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(resp)).endsWith("grant-exceeds-caller");
    }

    @Test
    void patchWithinCallersOwnGrantsSucceeds() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = manager(t, "m2@a.com", "+919500000003");
        SeededStaff target = manager(t, "t2@a.com", "+919500000004");
        UUID lesser = seedRole(t.id(), "Lesser", false, BILLING_VIEW);

        assertThat(patch(loginAndGetAccessToken(mgr), target.id(), Map.of("roleId", lesser.toString())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void nonOwnerCannotAssignOwnerRoleByPatchOrInvite() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        // a non-Owner who nonetheless holds every grant: still refused, the Owner role is Owner-only
        SeededStaff admin = seedStaff(t, seedRole(t.id(), "Admin", false, fullGrant("staff"), fullGrant("patients"), fullGrant("queue"),
                fullGrant("setup"), fullGrant("clinical"), fullGrant("billing"), fullGrant("specialty_dental"), fullGrant("reports"),
                fullGrant("pharmacy"), fullGrant("packages"), fullGrant("nursing"), fullGrant("departments")), "ad3@a.com", "+919500000005", false);
        SeededStaff target = manager(t, "t3@a.com", "+919500000006");
        String token = loginAndGetAccessToken(admin);

        ResponseEntity<Map> inv = invite(token, ownerRole, "inv3@a.com", "+919500000007");
        ResponseEntity<Map> pat = patch(token, target.id(), Map.of("roleId", ownerRole.toString()));

        assertThat(inv.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(inv)).endsWith("owner-role-restricted");
        assertThat(pat.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(pat)).endsWith("owner-role-restricted");
    }

    @Test
    void nonOwnerCannotDemoteAnOwner() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "ow4@a.com", "+919500000008", false);
        SeededStaff mgr = manager(t, "m4@a.com", "+919500000009");
        UUID lesser = seedRole(t.id(), "Lesser4", false, BILLING_VIEW);

        ResponseEntity<Map> resp = patch(loginAndGetAccessToken(mgr), owner.id(), Map.of("roleId", lesser.toString()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(resp)).endsWith("owner-role-restricted");
    }

    @Test
    void inviteWithRoleRicherThanCallerIsRefused() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = manager(t, "m5@a.com", "+919500000010");
        UUID rich = seedRole(t.id(), "Rich5", false, fullGrant("staff"), BILLING_REFUND);

        ResponseEntity<Map> resp = invite(loginAndGetAccessToken(mgr), rich, "inv5@a.com", "+919500000011");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(resp)).endsWith("grant-exceeds-caller");
    }

    @Test
    void ownerCanAssignAnyRoleIncludingOwner() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, ownerRole, "ow6@a.com", "+919500000012", false);
        SeededStaff target = manager(t, "t6@a.com", "+919500000013");
        String token = loginAndGetAccessToken(owner);

        assertThat(patch(token, target.id(), Map.of("roleId", ownerRole.toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invite(token, ownerRole, "inv6@a.com", "+919500000014").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void scopeAndFieldGrantWideningBeyondCallersOwnIsRefused() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = manager(t, "m7@a.com", "+919500000015");
        SeededStaff target = manager(t, "t7@a.com", "+919500000016");
        SeededStaff wideTarget = manager(t, "t7b@a.com", "+919500000021");
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET scope = 'own_patients_only' WHERE id IN (?, ?)", mgr.id(), target.id()));
        String token = loginAndGetAccessToken(mgr);

        ResponseEntity<Map> wide = patch(token, target.id(), Map.of("scope", "all_clinic_patients"));
        ResponseEntity<Map> narrow = patch(token, wideTarget.id(), Map.of("scope", "own_patients_only"));
        ResponseEntity<Map> fields = patch(token, target.id(), Map.of("fieldGrants", List.of("phone")));

        assertThat(wide.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(wide)).endsWith("grant-exceeds-caller");
        assertThat(narrow.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fields.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void roleChangeIsAuditedWithBeforeAfterAndRevokesTargetSessionsAndPublishesEvent() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "ow8@a.com", "+919500000017", false);
        SeededStaff target = manager(t, "t8@a.com", "+919500000018");
        UUID lesser = seedRole(t.id(), "Lesser8", false, BILLING_VIEW);
        String targetToken = loginAndGetAccessToken(target);
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(targetToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(patch(loginAndGetAccessToken(owner), target.id(), Map.of("roleId", lesser.toString(), "scope", "own_patients_only"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(targetToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> audit = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT before::text AS b, after::text AS a FROM audit_log WHERE action = 'staff.role_changed' AND entity_id = ?", target.id()));
        assertThat((String) audit.get("b")).contains("\"scope\"", "\"fieldGrants\"", "roleId");
        assertThat((String) audit.get("a")).contains(lesser.toString(), "own_patients_only");
        Integer events = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'iam.staff_role.changed.v1' AND payload->>'staffId' = ?",
                Integer.class, target.id().toString()));
        assertThat(events).isEqualTo(1);
    }

    @Test
    void unrelatedPatchWritesNoRoleAuditAndKeepsSessions() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "ow9@a.com", "+919500000019", false);
        SeededStaff target = manager(t, "t9@a.com", "+919500000020");
        String targetToken = loginAndGetAccessToken(target);

        patch(loginAndGetAccessToken(owner), target.id(), Map.of("roleId", inTenantTx(t.id(), () ->
                jdbc.queryForObject("SELECT role_id FROM staff WHERE id = ?", UUID.class, target.id())).toString()));

        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(targetToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'staff.role_changed' AND entity_id = ?", Integer.class, target.id()))).isZero();
    }
}
