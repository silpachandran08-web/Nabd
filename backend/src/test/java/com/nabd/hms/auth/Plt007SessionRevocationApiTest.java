package com.nabd.hms.auth;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-007: OperatorLiveness, TenantSuspendRevokesSessions, RoleChangeRevokes, AbsoluteLifetime, StepUpBinding and
 * one-tap staff suspension (NB-047). One class so the six criteria share one Spring context.
 */
class Plt007SessionRevocationApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    // ---- AC1: operator logout -> access token 401 ----

    @Test
    void operatorLogoutKillsTheAccessTokenOnTheNextRequest() {
        SeededOperator op = seedOperator("plt007-op-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String access = platformLoginAndGetAccessToken(op);
        assertThat(exchange("/v1/platform/auth/me", HttpMethod.GET, authed(access), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(exchange("/v1/platform/auth/logout", HttpMethod.POST, authed(access), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(exchange("/v1/platform/auth/me", HttpMethod.GET, authed(access), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/platform/tenants/" + UUID.randomUUID() + "/lifecycle", HttpMethod.GET, authed(access), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void operatorSessionRevokedFromAnotherDeviceIsAlso401() {
        SeededOperator op = seedOperator("plt007-op2-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String a = platformLoginAndGetAccessToken(op);
        String b = platformLoginAndGetAccessToken(op);
        String sidA = claim(a, "sid");
        assertThat(exchange("/v1/platform/auth/sessions/" + sidA, HttpMethod.DELETE, authed(b), Void.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(exchange("/v1/platform/auth/me", HttpMethod.GET, authed(a), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/platform/auth/me", HttpMethod.GET, authed(b), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- AC2: tenant suspended / offboarded ----

    @Test
    void suspendingATenantRevokesEverySessionAndRefusesRefreshAndWorkspaceSelect() {
        SeededOwner owner = seedOwner("plt007-own-" + UUID.randomUUID() + "@a.com");
        SeededTenant clinic = seedClinicInBrand(seedBrand(owner, "Brand"));
        SeededTenant other = seedTenant();
        UUID role = receptionistRole(clinic.id());
        Map<String, Object> s1 = login(seedStaff(clinic, role, "rec1@a.com", "+919300000001", false));
        Map<String, Object> s2 = login(seedStaff(clinic, role, "rec2@a.com", "+919300000002", false));
        Map<String, Object> otherTenant = login(seedStaff(other, receptionistRole(other.id()), "rec3@a.com", "+919300000003", false));

        ResponseEntity<Map> t = transition(clinic.id(), "suspended");
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(liveSessions(clinic.id())).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM sessions WHERE tenant_id = ? AND revoked_reason = 'tenant_suspended'",
                Integer.class, clinic.id())).isEqualTo(2);
        for (Map<String, Object> s : List.of(s1, s2)) {
            assertThat(get("/v1/queue", (String) s.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            ResponseEntity<Map> r = refresh((String) s.get("refreshToken"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(String.valueOf(r.getBody().get("type"))).endsWith("TENANT_INACTIVE");
        }
        // another tenant is untouched
        assertThat(get("/v1/queue", (String) otherTenant.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh((String) otherTenant.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> select = exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(ownerLoginPendingToken(owner), Map.of("clinicId", clinic.id().toString())), Map.class);
        assertThat(select.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(select.getBody().get("type"))).endsWith("TENANT_INACTIVE");

        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.session.revoked.v1' "
                + "AND payload->>'reasonCode' = 'tenant_suspended' AND payload->>'scope' = 'all_sessions'", Integer.class, clinic.id())).isEqualTo(2); // OPS-015 schema: one per staff member

        // reactivated: a fresh sign-in works again (old sessions stay dead)
        assertThat(transition(clinic.id(), "active").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh((String) s1.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(ownerLoginPendingToken(owner), Map.of("clinicId", clinic.id().toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void offboardingATenantRevokesSessionsToo() {
        SeededTenant clinic = seedTenant();
        Map<String, Object> s = login(seedStaff(clinic, receptionistRole(clinic.id()), "rec4@a.com", "+919300000004", false));
        assertThat(transition(clinic.id(), "offboarding").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveSessions(clinic.id())).isEqualTo(1); // offboarding is the export window; offboarded ends access
        assertThat(transition(clinic.id(), "offboarded").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveSessions(clinic.id())).isZero();
        ResponseEntity<Map> r = refresh((String) s.get("refreshToken"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("TENANT_INACTIVE");
    }

    @Test
    void bulkRevokeOf500SessionsIsOneFastUpdate() {
        SeededTenant clinic = seedTenant();
        SeededStaff staff = seedStaff(clinic, receptionistRole(clinic.id()), "rec5@a.com", "+919300000005", false);
        su.update("INSERT INTO sessions (tenant_id, staff_id, family_id, token_hash, expires_at) "
                + "SELECT ?, ?, gen_random_uuid(), md5(random()::text || g), now() + interval '1 day' FROM generate_series(1, 500) g",
                clinic.id(), staff.id());
        long t0 = System.nanoTime();
        assertThat(transition(clinic.id(), "suspended").getStatusCode()).isEqualTo(HttpStatus.OK);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(liveSessions(clinic.id())).isZero();
        assertThat(ms).as("transition incl. revoke of 500 sessions, ms").isLessThan(1000);
    }

    // ---- AC3: role / scope change ----

    @Test
    void roleChangeSignsTheReceptionistOutAndTheNextSignInCarriesTheNewPermissions() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "own@a.com", "+919300000010", true);
        String ownerAccess = mfaLogin(clinic, owner);
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "rec10@a.com", "+919300000011", false);
        Map<String, Object> tab1 = login(rec);
        Map<String, Object> tab2 = login(rec);
        assertThat(claim((String) tab1.get("accessToken"), "permissions")).doesNotContain("patients:view");
        UUID newRole = seedRole(clinic.id(), "Front desk plus", false,
                new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));

        ResponseEntity<Map> patch = exchange("/v1/staff/" + rec.id(), HttpMethod.PATCH,
                authedJsonBody(ownerAccess, Map.of("roleId", newRole.toString())), Map.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);

        for (Map<String, Object> tab : List.of(tab1, tab2)) {
            assertThat(get("/v1/queue", (String) tab.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refresh((String) tab.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(su.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_reason = 'role_changed'",
                Integer.class, rec.id())).isEqualTo(2);
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.session.revoked.v1' "
                + "AND payload->>'staffId' = ? AND payload->>'reasonCode' = 'role_changed' AND payload->>'scope' = 'all_sessions'", // OPS-015 schema: no session ids
                Integer.class, clinic.id(), rec.id().toString())).isEqualTo(1);
        // the owner's own session is untouched
        assertThat(get("/v1/staff", ownerAccess).getStatusCode()).isEqualTo(HttpStatus.OK);

        String fresh = (String) login(rec).get("accessToken");
        assertThat(claim(fresh, "permissions")).contains("patients:view");
        assertThat(claim(fresh, "roleId")).isEqualTo(newRole.toString());
    }

    @Test
    void scopeChangeAlsoSignsOutButAnUnrelatedPatchDoesNot() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "own2@a.com", "+919300000012", true);
        String ownerAccess = mfaLogin(clinic, owner);
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "rec12@a.com", "+919300000013", false);
        String a = (String) login(rec).get("accessToken");

        // same role again = no change -> session survives
        assertThat(exchange("/v1/staff/" + rec.id(), HttpMethod.PATCH,
                authedJsonBody(ownerAccess, Map.of("roleId", rec.roleId().toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/queue", a).getStatusCode()).isEqualTo(HttpStatus.OK);

        String current = su.queryForObject("SELECT scope FROM staff WHERE id = ?", String.class, rec.id());
        String next = "all_clinic_patients".equals(current) ? "own_patients_only" : "all_clinic_patients";
        assertThat(exchange("/v1/staff/" + rec.id(), HttpMethod.PATCH,
                authedJsonBody(ownerAccess, Map.of("scope", next)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/queue", a).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- AC4: 12-hour refresh-family cap ----

    @Test
    void refreshFamilyOlderThan12HoursIsRefusedWithSessionMaxAge() {
        SeededTenant clinic = seedTenant();
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "rec20@a.com", "+919300000020", false);
        Map<String, Object> s = login(rec);
        // rotation keeps the family's start
        Map<String, Object> rotated = refresh((String) s.get("refreshToken")).getBody();
        assertThat(su.queryForObject("SELECT count(DISTINCT family_started_at) FROM sessions WHERE staff_id = ?", Integer.class, rec.id())).isEqualTo(1);

        // 11 h 59 m: still fine
        backdateFamily(rec.id(), "11 hours 59 minutes");
        Map<String, Object> ok = refresh((String) rotated.get("refreshToken")).getBody();
        assertThat(ok).containsKey("accessToken");

        // idle-lock unlock does not extend the cap
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, authedJsonBody((String) ok.get("accessToken"), Map.of("pin", STAFF_PIN)), Void.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
        backdateFamily(rec.id(), "12 hours 1 minute");

        ResponseEntity<Map> refused = refresh((String) ok.get("refreshToken"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(refused.getBody().get("type"))).endsWith("SESSION_MAX_AGE");
        assertThat(su.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_reason = 'max_age'", Integer.class, rec.id()))
                .isEqualTo(1);
        // presenting it again still says why
        assertThat(String.valueOf(refresh((String) ok.get("refreshToken")).getBody().get("type"))).endsWith("SESSION_MAX_AGE");

        // signing in again starts a new family with a new clock
        Map<String, Object> again = login(rec);
        assertThat(refresh((String) again.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void operatorRefreshFamilyIsCappedToo() {
        SeededOperator op = seedOperator("plt007-op3-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        ResponseEntity<Map> l = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class);
        String rt = (String) l.getBody().get("refreshToken");
        su.update("UPDATE master.sessions SET family_started_at = now() - interval '12 hours 1 minute' WHERE operator_id = ?", op.id());
        ResponseEntity<Map> r = http.postForEntity(url("/v1/platform/auth/refresh"), jsonBody(Map.of("refreshToken", rt)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("SESSION_MAX_AGE");
    }

    // ---- AC5: step-up bound to its session ----

    @Test
    void stepUpTokenFromSessionAIsRefusedWithSessionB() {
        SeededTenant clinic = seedTenant();
        UUID full = seedFullAccessRole(clinic.id());
        SeededStaff owner = seedStaff(clinic, full, "own30@a.com", "+919300000030", true);
        SeededStaff target = seedStaff(clinic, receptionistRole(clinic.id()), "rec30@a.com", "+919300000031", false);
        String sessionA = mfaLogin(clinic, owner);
        String sessionB = mfaLogin(clinic, owner);
        String stepUpA = stepUp(sessionA);
        assertThat(claim(stepUpA, "sid")).isEqualTo(claim(sessionA, "sid"));
        assertThat(claim(stepUpA, "tenantId")).isEqualTo(clinic.id().toString());

        ResponseEntity<Map> wrong = suspend(sessionB, target.id(), stepUpA);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(wrong.getBody().get("type"))).endsWith("STEP_UP_INVALID");
        assertThat(su.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, target.id())).isEqualTo("active");

        assertThat(suspend(sessionA, target.id(), stepUpA).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---- AC6: one-tap suspension ends every device ----

    @Test
    void suspendingAStaffMemberEndsAllTheirSessionsOnAllDevices() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "own40@a.com", "+919300000040", true);
        String ownerAccess = mfaLogin(clinic, owner);
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "rec40@a.com", "+919300000041", false);
        List<Map<String, Object>> devices = List.of(login(rec), login(rec), login(rec));

        assertThat(suspend(ownerAccess, rec.id(), stepUp(ownerAccess)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        for (Map<String, Object> d : devices) {
            assertThat(get("/v1/queue", (String) d.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refresh((String) d.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(su.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_reason = 'suspended'", Integer.class, rec.id()))
                .isEqualTo(3);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'staff.suspended' AND entity_id = ?",
                Integer.class, clinic.id(), rec.id())).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.session.revoked.v1' "
                + "AND payload->>'staffId' = ? AND payload->>'reasonCode' = 'suspended'", Integer.class, clinic.id(), rec.id().toString())).isEqualTo(1);
        // and they cannot sign back in
        assertThat(http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", clinic.slug(), "email", rec.email(), "pin", STAFF_PIN)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/staff", ownerAccess).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- helpers ----

    private UUID receptionistRole(UUID tenantId) {
        return seedRole(tenantId, "Receptionist", false, new ModuleGrant("queue", true, true, true, false, false, false, false));
    }

    private Map<String, Object> login(SeededStaff s) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).as("login %s: %s", s.email(), r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private String mfaLogin(SeededTenant tenant, SeededStaff staff) {
        ResponseEntity<Map> l = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", l.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        assertThat(v.getStatusCode()).as("mfa verify: %s", v.getBody()).isEqualTo(HttpStatus.OK);
        return (String) v.getBody().get("accessToken");
    }

    private String stepUp(String access) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/mfa/verify"),
                new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", h), Map.class);
        assertThat(r.getStatusCode()).as("step-up: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("stepUpToken");
    }

    private ResponseEntity<Map> suspend(String access, UUID staffId, String stepUp) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.set("X-Step-Up-Token", stepUp);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(url("/v1/staff/" + staffId + "/suspend"), HttpMethod.POST, new HttpEntity<>(h), Map.class);
    }

    private ResponseEntity<Map> refresh(String refreshToken) {
        return http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", refreshToken)), Map.class);
    }

    private ResponseEntity<String> get(String path, String access) {
        return exchange(path, HttpMethod.GET, authed(access), String.class);
    }

    private ResponseEntity<Map> transition(UUID tenantId, String to) {
        SeededOperator op = seedOperator("plt007-sa-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        return exchange("/v1/platform/tenants/" + tenantId + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(platformLoginAndGetAccessToken(op), Map.of("toStatus", to, "reason", "plt-007 test")), Map.class);
    }

    private int liveSessions(UUID tenantId) {
        return su.queryForObject("SELECT count(*) FROM sessions WHERE tenant_id = ? AND revoked_at IS NULL", Integer.class, tenantId);
    }

    private void backdateFamily(UUID staffId, String age) {
        su.update("UPDATE sessions SET family_started_at = now() - ?::interval WHERE staff_id = ?", age, staffId);
    }

    @SuppressWarnings("unchecked")
    private String claim(String token, String name) {
        try {
            Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
            return String.valueOf(c.get(name));
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
