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

/** Independent QA (S10 PLT-015): nobody can hand out more than they hold; every change audited; sessions revoked. */
class S10QaRoleEscalationTest extends ApiTestBase {

    private static ModuleGrant g(String m, boolean v, boolean c, boolean e, boolean d, boolean a, boolean refund) {
        return new ModuleGrant(m, v, c, e, d, a, refund, false);
    }

    /** Manager: may view/create/edit staff, view patients. No billing at all. */
    private UUID managerRole(UUID tid) {
        return seedRole(tid, "Mgr" + UUID.randomUUID().toString().substring(0, 6), false,
                g("staff", true, true, true, false, false, false), g("patients", true, false, false, false, false, false));
    }

    private UUID refundRole(UUID tid) {
        return seedRole(tid, "Refund" + UUID.randomUUID().toString().substring(0, 6), false,
                g("billing", true, true, true, false, false, true), g("patients", true, false, false, false, false, false));
    }

    private UUID readOnlyRole(UUID tid) {
        return seedRole(tid, "RO" + UUID.randomUUID().toString().substring(0, 6), false, g("patients", true, false, false, false, false, false));
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(1000);
    private int n;

    private SeededStaff staff(SeededTenant t, UUID role) {
        n++;
        return seedStaff(t, role, "p15s" + n + UUID.randomUUID().toString().substring(0, 4) + "@q.com", "+91940040" + String.format("%04d", SEQ.incrementAndGet()), false);
    }

    private ResponseEntity<Map> patch(String token, UUID id, Map<String, Object> body) {
        return exchange("/v1/staff/" + id, HttpMethod.PATCH, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> invite(String token, UUID roleId, String scope) {
        n++;
        Map<String, Object> b = new java.util.HashMap<>(Map.of("email", "inv" + n + UUID.randomUUID().toString().substring(0, 4) + "@q.com", "name", "Invitee",
                "mobilePhone", "+91940050" + String.format("%04d", SEQ.incrementAndGet()), "roleId", roleId.toString()));
        if (scope != null) b.put("scope", scope);
        return exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, b), Map.class);
    }

    private UUID ownerRoleId(UUID tid) {
        return seedFullAccessRole(tid); // built-in "Owner"
    }

    private String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    private int auditRows(SeededTenant t, UUID target) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='staff.role_changed' AND entity_id=?", Integer.class, target));
    }

    private UUID roleOf(SeededTenant t, UUID staffId) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT role_id FROM staff WHERE id=?", UUID.class, staffId));
    }

    private boolean sessionAlive(String access) {
        return exchange("/v1/auth/sessions", HttpMethod.GET, authed(access), String.class).getStatusCode() == HttpStatus.OK;
    }

    private ResponseEntity<Map> refresh(String rt) {
        return http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", rt)), Map.class);
    }

    private Map<String, Object> loginTokens(SeededStaff s) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class).getBody();
    }

    // ---- AC1 ----
    @Test
    void ac1_patchToRoleWithBillingRefundThatCallerLacks_is403GrantExceedsCaller_nothingChanges() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = staff(t, managerRole(t.id()));
        UUID ro = readOnlyRole(t.id());
        SeededStaff colleague = staff(t, ro);
        Map<String, Object> ct = loginTokens(colleague);
        ResponseEntity<Map> r = patch(loginAndGetAccessToken(mgr), colleague.id(), Map.of("roleId", refundRole(t.id()).toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r)).endsWith("grant-exceeds-caller");
        assertThat(roleOf(t, colleague.id())).isEqualTo(ro);
        assertThat(auditRows(t, colleague.id())).isZero();
        assertThat(sessionAlive((String) ct.get("accessToken"))).isTrue();
    }

    @Test
    void ac1_selfEscalation_callerPatchesSelfToRicherRole_is403() {
        SeededTenant t = seedTenant();
        UUID mr = managerRole(t.id());
        SeededStaff mgr = staff(t, mr);
        ResponseEntity<Map> r = patch(loginAndGetAccessToken(mgr), mgr.id(), Map.of("roleId", refundRole(t.id()).toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(roleOf(t, mgr.id())).isEqualTo(mr);
    }

    @Test
    void ac1_boundary_roleExactlyEqualToCallersOwn_isAllowed_andStrictlyLargerByOnePermissionIsNot() {
        SeededTenant t = seedTenant();
        UUID mr = managerRole(t.id());
        SeededStaff mgr = staff(t, mr);
        SeededStaff target = staff(t, readOnlyRole(t.id()));
        String tok = loginAndGetAccessToken(mgr);
        // identical grants to the caller's own (a different role row, same permissions)
        UUID twin = seedRole(t.id(), "Twin" + UUID.randomUUID().toString().substring(0, 5), false,
                g("staff", true, true, true, false, false, false), g("patients", true, false, false, false, false, false));
        assertThat(patch(tok, target.id(), Map.of("roleId", twin.toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        // one extra bit (staff:delete)
        UUID plusOne = seedRole(t.id(), "Plus" + UUID.randomUUID().toString().substring(0, 5), false,
                g("staff", true, true, true, true, false, false), g("patients", true, false, false, false, false, false));
        ResponseEntity<Map> r = patch(tok, target.id(), Map.of("roleId", plusOne.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r)).endsWith("grant-exceeds-caller");
        assertThat(roleOf(t, target.id())).isEqualTo(twin);
    }

    @Test
    void ac1_inviteWithRoleBeyondCallers_is403GrantExceedsCaller_andNoStaffRowCreated() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = staff(t, managerRole(t.id()));
        int before = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class));
        ResponseEntity<Map> r = invite(loginAndGetAccessToken(mgr), refundRole(t.id()), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r)).endsWith("grant-exceeds-caller");
        assertThat((int) inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class))).isEqualTo(before);
        // a role inside the caller's own grants is fine
        assertThat(invite(loginAndGetAccessToken(mgr), readOnlyRole(t.id()), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- AC2 ----
    @Test
    void ac2_nonOwnerInvitesWithBuiltInOwnerRole_is403_evenThoughCallerHoldsStaffCreate() {
        SeededTenant t = seedTenant();
        UUID owner = ownerRoleId(t.id());
        SeededStaff mgr = staff(t, managerRole(t.id()));
        ResponseEntity<Map> r = invite(loginAndGetAccessToken(mgr), owner, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff WHERE role_id=?", Integer.class, owner))).isZero();
    }

    @Test
    void ac2_nonOwnerPatchesColleagueOrSelfToOwner_is403_andNonOwnerCannotDemoteAnOwner() {
        SeededTenant t = seedTenant();
        UUID owner = ownerRoleId(t.id());
        SeededStaff mgr = staff(t, managerRole(t.id()));
        SeededStaff friend = staff(t, readOnlyRole(t.id()));
        SeededStaff theOwner = staff(t, owner);
        String tok = loginAndGetAccessToken(mgr);
        ResponseEntity<Map> toFriend = patch(tok, friend.id(), Map.of("roleId", owner.toString()));
        assertThat(toFriend.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(toFriend)).endsWith("owner-role-restricted");
        assertThat(patch(tok, mgr.id(), Map.of("roleId", owner.toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch(tok, theOwner.id(), Map.of("roleId", readOnlyRole(t.id()).toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(roleOf(t, friend.id())).isNotEqualTo(owner);
        assertThat(roleOf(t, mgr.id())).isNotEqualTo(owner);
        assertThat(roleOf(t, theOwner.id())).isEqualTo(owner);
    }

    @Test
    void ac2_roleEditEndpoint_stillRefusesGrantsBeyondCallers() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = SeededStaffWith(t, seedRole(t.id(), "RoleMgr" + UUID.randomUUID().toString().substring(0, 4), false,
                g("staff", true, true, true, false, false, false), g("setup", true, true, true, false, false, false)));
        String tok = loginAndGetAccessToken(mgr);
        // the roles controller needs a permission to reach the service; whichever way it is gated the escalation must not succeed
        ResponseEntity<Map> r = exchange("/v1/roles", HttpMethod.POST, authedJsonBody(tok, Map.of("name", "Sneaky", "mfaRequired", false,
                "grants", List.of(Map.of("module", "billing", "view", true, "create", true, "edit", true, "delete", true, "approve", true, "refundDiscount", true, "export", true)))), Map.class);
        assertThat(r.getStatusCode().value()).isIn(400, 403);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM roles WHERE name='Sneaky'", Integer.class))).isZero();
    }

    private SeededStaff SeededStaffWith(SeededTenant t, UUID role) {
        return staff(t, role);
    }

    // ---- AC3: audit before/after ----
    @Test
    @SuppressWarnings("unchecked")
    void ac3_roleChange_writesAuditWithBeforeAndAfterRoleScopeAndFieldGrants() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff owner = staff(t, ownerRoleId(t.id()));
        UUID r1 = readOnlyRole(t.id()), r2 = managerRole(t.id());
        SeededStaff target = staff(t, r1);
        ResponseEntity<Map> r = patch(loginAndGetAccessToken(owner), target.id(), Map.of("roleId", r2.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = inTenantTx(t.id(), () -> jdbc.queryForMap("SELECT actor_id, before::text b, after::text a FROM audit_log WHERE action='staff.role_changed' AND entity_id=?", target.id()));
        assertThat(row.get("actor_id")).isEqualTo(owner.id());
        Map<String, Object> b = objectMapper.readValue((String) row.get("b"), Map.class), a = objectMapper.readValue((String) row.get("a"), Map.class);
        assertThat(b.get("roleId")).isEqualTo(r1.toString());
        assertThat(a.get("roleId")).isEqualTo(r2.toString());
        assertThat(b).containsKeys("scope", "fieldGrants");
        assertThat(a).containsKeys("scope", "fieldGrants");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac3_scopeOnlyChange_andFieldGrantOnlyChange_areEachAudited_withTheChangedValue() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff owner = staff(t, ownerRoleId(t.id()));
        SeededStaff target = staff(t, readOnlyRole(t.id()));
        String tok = loginAndGetAccessToken(owner);
        assertThat(patch(tok, target.id(), Map.of("scope", "own_patients_only")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditRows(t, target.id())).isEqualTo(1);
        assertThat(patch(tok, target.id(), Map.of("fieldGrants", List.of("nric"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditRows(t, target.id())).isEqualTo(2);
        List<Map<String, Object>> rows = inTenantTx(t.id(), () -> jdbc.queryForList("SELECT before::text b, after::text a FROM audit_log WHERE action='staff.role_changed' AND entity_id=? ORDER BY id", target.id()));
        Map<String, Object> a0 = objectMapper.convertValue(readJson((String) rows.get(0).get("a")), Map.class), b0 = objectMapper.convertValue(readJson((String) rows.get(0).get("b")), Map.class);
        assertThat(b0.get("scope")).isEqualTo("all_clinic_patients");
        assertThat(a0.get("scope")).isEqualTo("own_patients_only");
        Map<String, Object> a1 = objectMapper.convertValue(readJson((String) rows.get(1).get("a")), Map.class);
        assertThat((List<String>) a1.get("fieldGrants")).containsExactly("nric");
    }

    private Object readJson(String s) throws Exception {
        return objectMapper.readValue(s, Map.class);
    }

    @Test
    void ac3_changeThatChangesNothing_writesNoRoleChangedAudit_andKeepsSessions() {
        SeededTenant t = seedTenant();
        SeededStaff owner = staff(t, ownerRoleId(t.id()));
        UUID r1 = readOnlyRole(t.id());
        SeededStaff target = staff(t, r1);
        Map<String, Object> tt = loginTokens(target);
        assertThat(patch(loginAndGetAccessToken(owner), target.id(), Map.of("roleId", r1.toString(), "scope", "all_clinic_patients")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditRows(t, target.id())).isZero();
        assertThat(sessionAlive((String) tt.get("accessToken"))).isTrue();
    }

    @Test
    void ac3_escalationToScopeAllClinic_byOwnPatientsOnlyCaller_is403_andFieldGrantBeyondOwn_is403() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = staff(t, managerRole(t.id()));
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET scope='own_patients_only', field_grants='{}' WHERE id=?", mgr.id()));
        SeededStaff target = staff(t, readOnlyRole(t.id()));
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET scope='own_patients_only' WHERE id=?", target.id()));
        String tok = loginAndGetAccessToken(mgr);
        ResponseEntity<Map> widen = patch(tok, target.id(), Map.of("scope", "all_clinic_patients"));
        assertThat(widen.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(widen)).endsWith("grant-exceeds-caller");
        ResponseEntity<Map> field = patch(tok, target.id(), Map.of("fieldGrants", List.of("nric")));
        assertThat(field.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // invite without scope defaults to all_clinic_patients: must not let an own-patients-only caller mint a wider colleague
        ResponseEntity<Map> inv = invite(tok, readOnlyRole(t.id()), null);
        assertThat(inv.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT scope FROM staff WHERE id=?", String.class, target.id()))).isEqualTo("own_patients_only");
    }

    // ---- AC4: sessions revoked ----
    @Test
    void ac4_roleChange_revokesTargetSessions_refreshAndAccessTokenBothDead_callerUnaffected() {
        SeededTenant t = seedTenant();
        SeededStaff owner = staff(t, ownerRoleId(t.id()));
        SeededStaff target = staff(t, readOnlyRole(t.id()));
        Map<String, Object> tt = loginTokens(target);
        Map<String, Object> tt2 = loginTokens(target); // second device
        String ownerTok = loginAndGetAccessToken(owner);
        assertThat(sessionAlive((String) tt.get("accessToken"))).isTrue();
        assertThat(patch(ownerTok, target.id(), Map.of("roleId", managerRole(t.id()).toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh((String) tt.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh((String) tt2.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessionAlive((String) tt.get("accessToken"))).isFalse();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM sessions WHERE staff_id=? AND revoked_at IS NULL", Integer.class, target.id()))).isZero();
        assertThat(sessionAlive(ownerTok)).isTrue();
        // the target can sign in again and receives the NEW role's permissions
        @SuppressWarnings("unchecked")
        List<String> perms = (List<String>) claims((String) loginTokens(target).get("accessToken")).get("permissions");
        assertThat(perms).contains("staff:edit");
    }

    @Test
    void ac4_roleChange_publishesIamStaffRoleChangedEvent() {
        SeededTenant t = seedTenant();
        SeededStaff owner = staff(t, ownerRoleId(t.id()));
        UUID r1 = readOnlyRole(t.id()), r2 = managerRole(t.id());
        SeededStaff target = staff(t, r1);
        patch(loginAndGetAccessToken(owner), target.id(), Map.of("roleId", r2.toString()));
        String payload = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE event_type='iam.staff_role.changed.v1' AND payload->>'staffId'=?", String.class, target.id().toString()));
        assertThat(payload).contains(r1.toString()).contains(r2.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claims(String token) {
        try {
            return objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- AC5: owner can assign anything ----
    @Test
    void ac5_ownerAssignsAnyRoleIncludingOwner_viaPatchAndInvite() {
        SeededTenant t = seedTenant();
        UUID ownerRole = ownerRoleId(t.id());
        SeededStaff owner = staff(t, ownerRole);
        String tok = loginAndGetAccessToken(owner);
        SeededStaff a = staff(t, readOnlyRole(t.id()));
        assertThat(patch(tok, a.id(), Map.of("roleId", refundRole(t.id()).toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch(tok, a.id(), Map.of("roleId", ownerRole.toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roleOf(t, a.id())).isEqualTo(ownerRole);
        assertThat(invite(tok, ownerRole, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invite(tok, refundRole(t.id()), "own_patients_only").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(auditRows(t, a.id())).isEqualTo(2);
    }

    // ---- tenant isolation / auth ----
    @Test
    void tenantIsolation_roleOrStaffFromAnotherTenant_is404_andNoCrossTenantAuditOrChange() {
        SeededTenant a = seedTenant(), b = seedTenant();
        SeededStaff ownerA = staff(a, ownerRoleId(a.id()));
        SeededStaff targetA = staff(a, readOnlyRole(a.id()));
        SeededStaff targetB = staff(b, readOnlyRole(b.id()));
        UUID roleB = managerRole(b.id());
        String tok = loginAndGetAccessToken(ownerA);
        assertThat(patch(tok, targetA.id(), Map.of("roleId", roleB.toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch(tok, targetB.id(), Map.of("scope", "own_patients_only")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(invite(tok, roleB, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(auditRows(a, targetA.id())).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT scope FROM staff WHERE id=?", String.class, targetB.id()))).isEqualTo("all_clinic_patients");
    }

    @Test
    void permission_staffWithoutStaffEdit_cannotPatch_403_andInviteNeedsStaffCreate() {
        SeededTenant t = seedTenant();
        SeededStaff ro = staff(t, readOnlyRole(t.id()));
        SeededStaff target = staff(t, readOnlyRole(t.id()));
        String tok = loginAndGetAccessToken(ro);
        assertThat(patch(tok, target.id(), Map.of("scope", "own_patients_only")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invite(tok, readOnlyRole(t.id()), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/staff/" + target.id(), HttpMethod.PATCH, jsonBody(Map.of("scope", "own_patients_only")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
