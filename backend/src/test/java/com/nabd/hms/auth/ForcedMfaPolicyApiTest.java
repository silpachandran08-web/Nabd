package com.nabd.hms.auth;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.TotpTestCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-005: MFA is forced for the Owner role, refund/approve roles and every platform operator, whatever roles.mfa_required says. */
@TestPropertySource(properties = "app.auth.mfa.enforce-privileged=true")
@SuppressWarnings({"rawtypes", "unchecked"})
class ForcedMfaPolicyApiTest extends ApiTestBase {

    private ResponseEntity<Map> staffLogin(SeededStaff s) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
    }

    private SeededStaff staffWithRole(SeededTenant t, UUID roleId, boolean mfa) {
        return seedStaff(t, roleId, "s" + UUID.randomUUID().toString().substring(0, 8) + "@a.com", null, mfa);
    }

    private static ModuleGrant viewOnly(String module) {
        return new ModuleGrant(module, true, false, false, false, false, false, false);
    }

    private void assertSetupOnly(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("setupToken").doesNotContainKeys("accessToken", "refreshToken", "challengeId");
        // the setup token is not a session: no other endpoint accepts it
        String setup = (String) r.getBody().get("setupToken");
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(setup), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/patients", HttpMethod.GET, authed(setup), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void builtInOwnerRoleWithoutMfaGetsOnlyASetupToken() {
        SeededTenant t = seedTenant();
        assertSetupOnly(staffLogin(staffWithRole(t, seedFullAccessRole(t.id()), false)));
    }

    @Test
    void refundDiscountRoleWithoutMfaGetsOnlyASetupToken() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Cashier", false, new ModuleGrant("billing", true, true, false, false, false, true, false));
        assertSetupOnly(staffLogin(staffWithRole(t, role, false)));
    }

    @Test
    void approveRoleWithoutMfaGetsOnlyASetupToken() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Lead", false, new ModuleGrant("pharmacy", true, false, false, false, true, false, false));
        assertSetupOnly(staffLogin(staffWithRole(t, role, false)));
    }

    @Test
    void unprivilegedRoleStillSignsInWithPinOnly() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Front desk", false, viewOnly("queue"), viewOnly("patients"));
        ResponseEntity<Map> r = staffLogin(staffWithRole(t, role, false));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("accessToken");
    }

    @Test
    void privilegedRoleOnLoanAlsoForcesEnrolment() {
        SeededTenant t = seedTenant();
        UUID plain = seedRole(t.id(), "Nurse", false, viewOnly("queue"));
        UUID approver = seedRole(t.id(), "Approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        SeededStaff granter = staffWithRole(t, seedFullAccessRole(t.id()), true);
        SeededStaff s = staffWithRole(t, plain, false);
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO role_delegations (tenant_id, staff_id, delegated_role_id, granted_by, reason, starts_at, expires_at) "
                + "VALUES (?,?,?,?,'cover', now() - interval '1 minute', now() + interval '1 day')", t.id(), s.id(), approver, granter.id()));
        assertSetupOnly(staffLogin(s));
    }

    @Test
    void enrolmentWithTheSetupTokenThenSignInIsChallenged() {
        SeededTenant t = seedTenant();
        SeededStaff s = staffWithRole(t, seedFullAccessRole(t.id()), false);
        String setup = (String) staffLogin(s).getBody().get("setupToken");

        ResponseEntity<Map> enroll = http.postForEntity(url("/v1/auth/mfa/enroll"), jsonBody(Map.of("setupToken", setup)), Map.class);
        assertThat(enroll.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] secret = TotpTestCodes.base32((String) enroll.getBody().get("secretBase32"));
        ResponseEntity<Map> confirm = http.postForEntity(url("/v1/auth/mfa/confirm"),
                jsonBody(Map.of("setupToken", setup, "code", TotpTestCodes.code(secret, 0))), Map.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> again = staffLogin(s);
        assertThat(again.getBody()).containsKey("challengeId").doesNotContainKey("accessToken");
        ResponseEntity<Map> verified = http.postForEntity(url("/v1/auth/mfa/verify"),
                jsonBody(Map.of("challengeId", again.getBody().get("challengeId"), "code", TotpTestCodes.code(secret, 1))), Map.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).containsKey("accessToken");
    }

    @Test
    void aSessionThatBecamePrivilegedCannotRefreshWithoutMfa() {
        SeededTenant t = seedTenant();
        UUID plain = seedRole(t.id(), "Clerk", false, viewOnly("queue"));
        UUID approver = seedRole(t.id(), "Approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        SeededStaff s = staffWithRole(t, plain, false);
        String refresh = (String) staffLogin(s).getBody().get("refreshToken");
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET role_id = ? WHERE id = ?", approver, s.id()));

        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", refresh)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void inviteAcceptForAPrivilegedRoleReturnsASetupTokenNotASession() {
        SeededTenant t = seedTenant();
        UUID approver = seedRole(t.id(), "Approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        String raw = OpaqueTokens.generate();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO staff (tenant_id, role_id, email, name, status, invite_token_hash, invite_expires_at) "
                + "VALUES (?,?,?,'Invitee','invited',?, now() + interval '1 day')", t.id(), approver, "inv" + UUID.randomUUID().toString().substring(0, 6) + "@a.com",
                OpaqueTokens.sha256Hex(raw)));
        ResponseEntity<Map> r = http.postForEntity(url("/v1/staff/invitations/" + raw + "/accept"), jsonBody(Map.of("pin", "567890")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("setupToken").doesNotContainKey("accessToken");
    }

    // ── role edits cannot clear the invariant ──

    private String ownerSession(SeededTenant t) {
        SeededStaff owner = staffWithRole(t, seedFullAccessRole(t.id()), true);
        ResponseEntity<Map> c = staffLogin(owner);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"),
                jsonBody(Map.of("challengeId", c.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) v.getBody().get("accessToken");
    }

    private static Map<String, Object> grant(String module, boolean approve, boolean refund) {
        return Map.of("module", module, "view", true, "create", false, "edit", false, "delete", false,
                "approve", approve, "refundDiscount", refund, "export", false);
    }

    @Test
    void roleEditSettingMfaRequiredFalseOnAPrivilegedRoleIs422() {
        SeededTenant t = seedTenant();
        String tok = ownerSession(t);
        UUID approver = seedRole(t.id(), "Approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));

        ResponseEntity<Map> r = exchange("/v1/roles/" + approver, HttpMethod.PATCH, authedJsonBody(tok,
                Map.of("name", "Approver", "grants", List.of(grant("billing", true, false)), "mfaRequired", false)), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat((String) r.getBody().get("type")).endsWith("mfa-required-by-policy");

        ResponseEntity<Map> refund = exchange("/v1/roles/" + approver, HttpMethod.PATCH, authedJsonBody(tok,
                Map.of("name", "Approver", "grants", List.of(grant("billing", false, true)), "mfaRequired", false)), Map.class);
        assertThat(refund.getStatusCode().value()).isEqualTo(422);

        ResponseEntity<Map> create = exchange("/v1/roles", HttpMethod.POST, authedJsonBody(tok,
                Map.of("name", "Sneaky approver", "grants", List.of(grant("pharmacy", true, false)), "mfaRequired", false)), Map.class);
        assertThat(create.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void roleEditWithoutMfaFlagKeepsItAndReportsItRequired() {
        SeededTenant t = seedTenant();
        String tok = ownerSession(t);
        UUID approver = seedRole(t.id(), "Approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        ResponseEntity<Map> r = exchange("/v1/roles/" + approver, HttpMethod.PATCH, authedJsonBody(tok,
                Map.of("name", "Approver 2", "grants", List.of(grant("billing", true, false)))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("mfaRequired")).isEqualTo(true);

        UUID plain = seedRole(t.id(), "Viewer", false, viewOnly("queue"));
        ResponseEntity<Map> p = exchange("/v1/roles/" + plain, HttpMethod.PATCH, authedJsonBody(tok,
                Map.of("name", "Viewer", "grants", List.of(grant("queue", false, false)), "mfaRequired", false)), Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p.getBody().get("mfaRequired")).isEqualTo(false);
    }

    @Test
    void staffListShowsMfaStatusPerRow() {
        SeededTenant t = seedTenant();
        String tok = ownerSession(t);
        UUID approver = seedRole(t.id(), "Approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        UUID plain = seedRole(t.id(), "Viewer", false, viewOnly("queue"));
        SeededStaff needs = staffWithRole(t, approver, false);
        SeededStaff none = staffWithRole(t, plain, false);
        SeededStaff on = staffWithRole(t, approver, true);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) exchange("/v1/staff?limit=100", HttpMethod.GET, authed(tok), Map.class).getBody().get("data");
        Map<String, Object> byEmail = new java.util.HashMap<>();
        rows.forEach(row -> byEmail.put((String) row.get("email"), row.get("loginMethod")));
        assertThat(byEmail.get(needs.email())).isEqualTo("authenticator_setup_required");
        assertThat(byEmail.get(on.email())).isEqualTo("authenticator");
        assertThat(byEmail.get(none.email())).isNotIn("authenticator", "authenticator_setup_required");
    }

    // ── platform operators ──

    private ResponseEntity<Map> operatorLogin(SeededOperator op) {
        return http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class);
    }

    @Test
    void everyOperatorRoleWithoutMfaIsForcedIntoEnrolmentSuperAdminIncluded() {
        for (String role : List.of("support_engineer", "sre", "compliance_dpo", "super_admin", "implementation", "billing", "commercial")) {
            SeededOperator op = seedOperator(role + UUID.randomUUID().toString().substring(0, 6) + "@nabd.test", role, false);
            ResponseEntity<Map> r = operatorLogin(op);
            assertThat(r.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).as(role).containsKey("setupToken").doesNotContainKeys("accessToken", "challengeId");
            String setup = (String) r.getBody().get("setupToken");
            assertThat(exchange("/v1/platform/auth/me", HttpMethod.GET, authed(setup), Map.class).getStatusCode()).as(role).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void superAdminEnrolsWithTheSetupTokenAndIsThenChallenged() {
        SeededOperator op = seedOperator("sa" + UUID.randomUUID().toString().substring(0, 6) + "@nabd.test", "super_admin", false);
        String setup = (String) operatorLogin(op).getBody().get("setupToken");

        // a platform setup token does not enrol a staff member
        assertThat(http.postForEntity(url("/v1/auth/mfa/enroll"), jsonBody(Map.of("setupToken", setup)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity(url("/v1/platform/auth/mfa/enroll"), jsonBody(Map.of("setupToken", "nope")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> enroll = http.postForEntity(url("/v1/platform/auth/mfa/enroll"), jsonBody(Map.of("setupToken", setup)), Map.class);
        assertThat(enroll.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) enroll.getBody().get("otpauthUri")).startsWith("otpauth://totp/");
        byte[] secret = TotpTestCodes.base32((String) enroll.getBody().get("secretBase32"));

        assertThat(http.postForEntity(url("/v1/platform/auth/mfa/confirm"), jsonBody(Map.of("setupToken", setup, "code", "000000")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity(url("/v1/platform/auth/mfa/confirm"),
                jsonBody(Map.of("setupToken", setup, "code", TotpTestCodes.code(secret, 0))), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> again = operatorLogin(op);
        assertThat(again.getBody()).containsKey("challengeId").doesNotContainKey("setupToken");
        ResponseEntity<Map> verified = http.postForEntity(url("/v1/platform/auth/mfa/verify"),
                jsonBody(Map.of("challengeId", again.getBody().get("challengeId"), "code", TotpTestCodes.code(secret, 1))), Map.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).containsKey("accessToken");
    }

    @Test
    void fleetListShowsThePercentOfPrivilegedUsersEnrolled() {
        SeededTenant t = seedTenant();
        UUID owner = seedFullAccessRole(t.id());
        UUID approver = seedRole(t.id(), "Approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        UUID plain = seedRole(t.id(), "Viewer", false, viewOnly("queue"));
        staffWithRole(t, owner, true);
        staffWithRole(t, approver, false);
        staffWithRole(t, approver, true);
        staffWithRole(t, plain, false); // not privileged: not counted

        SeededOperator op = seedOperator("fl" + UUID.randomUUID().toString().substring(0, 6) + "@nabd.test", "super_admin", true);
        ResponseEntity<Map> c = operatorLogin(op);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/platform/auth/mfa/verify"),
                jsonBody(Map.of("challengeId", c.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        String tok = (String) v.getBody().get("accessToken");

        Map<String, Object> row = null;
        String cursor = null;
        do {
            Map body = exchange("/v1/platform/tenants?limit=100" + (cursor == null ? "" : "&cursor=" + cursor), HttpMethod.GET, authed(tok), Map.class).getBody();
            for (Map<String, Object> r : (List<Map<String, Object>>) body.get("data")) {
                if (t.id().toString().equals(r.get("id"))) row = r;
            }
            cursor = (String) ((Map) body.get("page")).get("nextCursor");
        } while (row == null && cursor != null);
        assertThat(row).isNotNull();
        assertThat(row.get("privilegedUsers")).isEqualTo(3);
        assertThat(row.get("privilegedMfaEnrolled")).isEqualTo(2);
        assertThat(row.get("privilegedMfaPercent")).isEqualTo(66);
    }
}
