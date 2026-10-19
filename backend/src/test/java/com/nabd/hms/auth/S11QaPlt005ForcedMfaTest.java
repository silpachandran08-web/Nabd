package com.nabd.hms.auth;

import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S11 QA (independent) PLT-005 with enforcement ON (the shared test profile sets app.auth.mfa.enforce-privileged=false,
 * so this context turns it back on): owner role, money approver, operator and owner account without MFA never get a session.
 */
@TestPropertySource(properties = "app.auth.mfa.enforce-privileged=true")
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaPlt005ForcedMfaTest extends ApiTestBase {

    @Autowired
    MfaPolicy policy;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<Map> pinLogin(SeededStaff s) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
    }

    /** The only thing a PIN buys is a setup token, and that token opens nothing but enrolment. */
    private void assertNoSessionOnlySetup(ResponseEntity<Map> r, String... probePaths) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("setupToken").doesNotContainKeys("accessToken", "refreshToken", "challengeId", "pendingToken", "workspaceToken");
        String setup = (String) r.getBody().get("setupToken");
        for (String p : probePaths) {
            assertThat(exchange(p, HttpMethod.GET, authed(setup), Map.class).getStatusCode()).as("setup token on " + p).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // nor can it be traded for a session
        assertThat(http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", setup)), Map.class).getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void enforcementIsActuallyOnInThisContext() {
        assertThat(policy.enforced()).isTrue();
    }

    @Test
    void builtInOwnerRoleWithoutMfaGetsNoSessionAndNoSessionRowIsCreated() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "own" + uniq() + "@qa.test", null, false);
        assertNoSessionOnlySetup(pinLogin(s), "/v1/staff", "/v1/patients", "/v1/queue", "/v1/auth/sessions");
        Integer sessions = su.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ?", Integer.class, s.id());
        assertThat(sessions).as("no refresh session minted").isZero();
    }

    @Test
    void moneyApproverAndRefunderWithoutMfaGetNoSession() {
        SeededTenant t = seedTenant();
        UUID approve = seedRole(t.id(), "QA approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        UUID refund = seedRole(t.id(), "QA refunder", false, new ModuleGrant("billing", true, false, false, false, false, true, false));
        for (UUID role : List.of(approve, refund)) {
            SeededStaff s = seedStaff(t, role, "m" + uniq() + "@qa.test", null, false);
            assertNoSessionOnlySetup(pinLogin(s), "/v1/queue", "/v1/patients");
        }
    }

    @Test
    void privilegedStaffCannotDodgeEnrolmentThroughWhatsAppOtp() {
        SeededTenant t = seedTenant();
        String phone = "+9196" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "otp" + uniq() + "@qa.test", phone, false);
        // a valid code on file (same hash scheme the request path stores)
        su.update("UPDATE staff SET whatsapp_otp_hash = ?, whatsapp_otp_expires_at = now() + interval '5 minutes' WHERE id = ?",
                com.nabd.hms.common.OpaqueTokens.sha256Hex("246810"), s.id());
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/otp/verify"),
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", phone, "code", "246810")), Map.class);
        assertNoSessionOnlySetup(r, "/v1/staff");
    }

    @Test
    void enrolledApproverIsChallengedNotHandedASession() {
        SeededTenant t = seedTenant();
        UUID approve = seedRole(t.id(), "QA approver", false, new ModuleGrant("pharmacy", true, false, false, false, true, false, false));
        ResponseEntity<Map> r = pinLogin(seedStaff(t, approve, "e" + uniq() + "@qa.test", null, true));
        assertThat(r.getBody()).containsKey("challengeId").doesNotContainKeys("accessToken", "setupToken");
    }

    @Test
    void boundaryPlainRoleStillGetsAPinSession() {
        SeededTenant t = seedTenant();
        UUID plain = seedRole(t.id(), "QA desk", false, new ModuleGrant("queue", true, true, true, false, false, false, false));
        ResponseEntity<Map> r = pinLogin(seedStaff(t, plain, "d" + uniq() + "@qa.test", null, false));
        assertThat(r.getBody()).containsKeys("accessToken", "refreshToken");
    }

    // ── role edits ──

    private String ownerToken(SeededTenant t) {
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o" + uniq() + "@qa.test", null, true);
        ResponseEntity<Map> c = pinLogin(owner);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", c.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) v.getBody().get("accessToken");
    }

    private static Map<String, Object> g(String module, boolean approve, boolean refund) {
        return Map.of("module", module, "view", true, "create", false, "edit", false, "delete", false, "approve", approve, "refundDiscount", refund, "export", false);
    }

    @Test
    void addingApproveWhileSettingMfaFalseIs422AndTheRoleIsUnchanged() {
        SeededTenant t = seedTenant();
        String tok = ownerToken(t);
        UUID plain = seedRole(t.id(), "QA clerk", false, new ModuleGrant("billing", true, false, false, false, false, false, false));
        ResponseEntity<Map> r = exchange("/v1/roles/" + plain, HttpMethod.PATCH, authedJsonBody(tok,
                Map.of("name", "QA clerk", "grants", List.of(g("billing", true, false)), "mfaRequired", false)), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(r.getBody().get("type"))).contains("mfa-required-by-policy");
        String grants = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT grants::text FROM roles WHERE id = ?", String.class, plain));
        assertThat(grants).as("refused edit wrote nothing").doesNotContain("\"approve\": true");
    }

    @Test
    void builtInOwnerRoleCannotBeSwitchedToPinOnly() {
        SeededTenant t = seedTenant();
        String tok = ownerToken(t);
        UUID ownerRole = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM roles WHERE tenant_id = ? AND built_in LIMIT 1", UUID.class, t.id()));
        ResponseEntity<Map> r = exchange("/v1/roles/" + ownerRole, HttpMethod.PATCH, authedJsonBody(tok,
                Map.of("name", "Owner", "grants", List.of(g("queue", false, false)), "mfaRequired", false)), Map.class);
        assertThat(r.getStatusCode().is4xxClientError()).as("edit refused: " + r.getStatusCode()).isTrue();
        // and a fresh Owner-role member without MFA is still forced into enrolment
        assertNoSessionOnlySetup(pinLogin(seedStaff(t, ownerRole, "o2" + uniq() + "@qa.test", null, false)));
    }

    @Test
    void roleOfAnotherTenantIsNotEditable() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String tokA = ownerToken(a);
        UUID roleB = seedRole(b.id(), "QA other", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        ResponseEntity<Map> r = exchange("/v1/roles/" + roleB, HttpMethod.PATCH, authedJsonBody(tokA,
                Map.of("name", "QA other", "grants", List.of(g("queue", false, false)), "mfaRequired", false)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── operators ──

    @Test
    void everyOperatorWithoutMfaGetsNoPlatformSession() {
        for (String role : List.of("support_engineer", "sre", "compliance_dpo", "super_admin")) {
            SeededOperator op = seedOperator("qa-" + role + uniq() + "@nabd.test", role, false);
            ResponseEntity<Map> r = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class);
            assertThat(r.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).as(role).containsKey("setupToken").doesNotContainKeys("accessToken", "refreshToken", "challengeId");
            String setup = (String) r.getBody().get("setupToken");
            for (String p : List.of("/v1/platform/tenants", "/v1/platform/audit-log", "/v1/platform/auth/me")) {
                assertThat(exchange(p, HttpMethod.GET, authed(setup), Map.class).getStatusCode()).as(role + " " + p).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
            Integer sessions = su.queryForObject("SELECT count(*) FROM master.sessions WHERE operator_id = ?", Integer.class, op.id());
            assertThat(sessions).as(role + ": no operator session row").isZero();
        }
    }

    // ── owner accounts ──

    @Test
    void ownerWithoutMfaCannotListOrSelectAWorkspace() {
        SeededOwner owner = seedOwner("qa-own" + uniq() + "@qa.test");
        SeededTenant clinic = seedClinicInBrand(seedBrand(owner, "QA brand " + uniq()));
        ResponseEntity<Map> r = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of("email", owner.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("setupToken").doesNotContainKeys("pendingToken", "workspaceToken", "accessToken");
        String setup = (String) r.getBody().get("setupToken");
        assertThat(exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(setup), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/owners/workspaces/select", HttpMethod.POST, authedJsonBody(setup, Map.of("clinicId", clinic.id().toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void enrolledOwnerNeedsTheTotpBeforeSelectWorks() {
        SeededOwner owner = seedOwner("qa-own" + uniq() + "@qa.test");
        SeededTenant clinic = seedClinicInBrand(seedBrand(owner, "QA brand " + uniq()));
        su.update("UPDATE owners SET mfa_enabled = true, mfa_secret_enc = ? WHERE id = ?",
                cipher.encrypt(TOTP_SECRET, FieldCipher.Aad.of("platform", "owners", "mfa_secret", owner.id())), owner.id());

        ResponseEntity<Map> login = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of("email", owner.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(login.getBody()).containsKey("challengeId").doesNotContainKeys("pendingToken", "workspaceToken", "setupToken");
        String challenge = (String) login.getBody().get("challengeId");
        // the challenge token is not a workspace token
        assertThat(exchange("/v1/owners/workspaces/select", HttpMethod.POST, authedJsonBody(challenge, Map.of("clinicId", clinic.id().toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // wrong code
        ResponseEntity<Map> bad = http.postForEntity(url("/v1/owners/auth/mfa/verify"), jsonBody(Map.of("challengeToken", challenge, "code", "000000")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> ok = http.postForEntity(url("/v1/owners/auth/mfa/verify"), jsonBody(Map.of("challengeToken", challenge, "code", currentTotpCode())), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        String ws = (String) ok.getBody().get("workspaceToken");
        ResponseEntity<Map> sel = exchange("/v1/owners/workspaces/select", HttpMethod.POST, authedJsonBody(ws, Map.of("clinicId", clinic.id().toString())), Map.class);
        assertThat(sel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sel.getBody()).containsKey("accessToken");
    }

    // ── visibility ──

    @Test
    void fleetShowsPrivilegedMfaPercentForATenant() {
        SeededTenant t = seedTenant();
        UUID approve = seedRole(t.id(), "QA approver", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        seedStaff(t, approve, "f1" + uniq() + "@qa.test", null, true);
        seedStaff(t, approve, "f2" + uniq() + "@qa.test", null, false);
        seedStaff(t, approve, "f3" + uniq() + "@qa.test", null, false);
        seedStaff(t, approve, "f4" + uniq() + "@qa.test", null, true);
        SeededOperator op = seedOperator("qa-fleet" + uniq() + "@nabd.test", "super_admin", true);
        ResponseEntity<Map> c = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of("challengeId", c.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        String tok = (String) v.getBody().get("accessToken");
        Map<String, Object> row = null;
        String cursor = null;
        do {
            ResponseEntity<Map> fleet = exchange("/v1/platform/tenants?limit=100" + (cursor == null ? "" : "&cursor=" + cursor), HttpMethod.GET, authed(tok), Map.class);
            assertThat(fleet.getStatusCode()).isEqualTo(HttpStatus.OK);
            for (Map<String, Object> x : (List<Map<String, Object>>) fleet.getBody().get("data")) {
                if (t.id().toString().equals(String.valueOf(x.get("id")))) row = x;
            }
            cursor = (String) ((Map) fleet.getBody().get("page")).get("nextCursor");
        } while (row == null && cursor != null);
        assertThat(row).as("tenant in fleet").isNotNull();
        assertThat(((Number) row.get("privilegedUsers")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("privilegedMfaEnrolled")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("privilegedMfaPercent")).intValue()).isEqualTo(50);
    }
}
