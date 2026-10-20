package com.nabd.hms.auth;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12 QA-2, PLT-007 (sessions end when they should), written from the story's acceptance criteria by an independent
 * tester. Positive, negative, boundary, tenant isolation and abuse cases (token replay across sessions, forged
 * step-up shapes, cross-tenant ids). Distinct accounts per scenario; TOTP offsets tracked per account (PLT-003 replay guard).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaPlt007SessionsEndTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());
    private final Map<UUID, Integer> totpUses = new HashMap<>();

    @Autowired
    JwtEncoder jwtEncoder;

    // ================= AC1: operator logout -> 401 (master.sessions liveness) =================

    @Test
    void ac1_operatorAccessTokenIs401TenSecondsAfterLogoutAndRefreshIsDeadToo() throws Exception {
        SeededOperator op = seedOperator("qa2-op-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        Map<String, Object> pair = platformLogin(op);
        String access = (String) pair.get("accessToken");
        assertThat(get("/v1/platform/auth/me", access).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/platform/auth/logout", HttpMethod.POST, authed(access), Void.class).getStatusCode().is2xxSuccessful()).isTrue();
        Thread.sleep(10_000); // the criterion's "10 s later"
        assertThat(get("/v1/platform/auth/me", access).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/platform/provisioning-jobs", access).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> r = http.postForEntity(url("/v1/platform/auth/refresh"), jsonBody(Map.of("refreshToken", pair.get("refreshToken"))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac1_operatorLogoutOnOneDeviceLeavesTheOtherDeviceAndOtherOperatorsAlive() {
        SeededOperator op = seedOperator("qa2-op2-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        SeededOperator other = seedOperator("qa2-op3-" + UUID.randomUUID() + "@nabd.health", "implementation", false);
        String laptop = platformLoginAndGetAccessToken(op);
        String phone = platformLoginAndGetAccessToken(op);
        String otherAccess = platformLoginAndGetAccessToken(other);
        exchange("/v1/platform/auth/logout", HttpMethod.POST, authed(laptop), Void.class);
        assertThat(get("/v1/platform/auth/me", laptop).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/platform/auth/me", phone).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/platform/auth/me", otherAccess).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac1_operatorSessionKilledDirectlyInMasterSessionsIsSeenOnTheNextRequest() {
        SeededOperator op = seedOperator("qa2-op4-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String access = platformLoginAndGetAccessToken(op);
        assertThat(get("/v1/platform/auth/me", access).getStatusCode()).isEqualTo(HttpStatus.OK);
        su.update("UPDATE master.sessions SET revoked_at = now(), revoked_reason = 'admin_revoke' WHERE id = ?::uuid", claim(access, "sid"));
        assertThat(get("/v1/platform/auth/me", access).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ================= AC2: tenant suspended / offboarded =================

    @Test
    void ac2_suspensionRevokesStaffAndOwnerClinicSessionsRefreshAndSelectAreRefusedOtherClinicUntouched() {
        SeededOwner owner = seedOwner("qa2-own-" + UUID.randomUUID() + "@a.com");
        SeededBrand brand = seedBrand(owner, "QA2 Brand");
        SeededTenant clinic = seedClinicInBrand(brand);
        SeededTenant sister = seedClinicInBrand(brand); // same owner, stays active
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec1@a.com", "+919310000001", false);
        Map<String, Object> recPair = login(rec);
        Map<String, Object> ownerClinic = select(owner, clinic).getBody();
        assertThat(get("/v1/queue", (String) ownerClinic.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.OK);

        long t0 = System.nanoTime();
        assertThat(transition(clinic.id(), "suspended").getStatusCode()).isEqualTo(HttpStatus.OK);
        // within 60 s: verified immediately after the 200 (well inside the window)
        for (Map<String, Object> p : List.of(recPair, ownerClinic)) {
            assertThat(get("/v1/queue", (String) p.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            ResponseEntity<Map> r = refresh((String) p.get("refreshToken"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(String.valueOf(r.getBody().get("type"))).endsWith("TENANT_INACTIVE");
        }
        assertThat((System.nanoTime() - t0) / 1_000_000_000L).isLessThan(60);
        assertThat(liveSessions(clinic.id())).isZero();

        ResponseEntity<Map> sel = select(owner, clinic);
        assertThat(sel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(sel.getBody().get("type"))).endsWith("TENANT_INACTIVE");
        assertThat(sel.getBody()).doesNotContainKey("accessToken");
        // tenant isolation: the owner's other clinic still works
        assertThat(select(owner, sister).getStatusCode()).isEqualTo(HttpStatus.OK);
        // and no new sign-in into the suspended clinic
        assertThat(rawLogin(rec).getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void ac2_offboardedClinicRefusesRefreshAndWorkspaceSelect() {
        SeededOwner owner = seedOwner("qa2-own2-" + UUID.randomUUID() + "@a.com");
        SeededTenant clinic = seedClinicInBrand(seedBrand(owner, "QA2 Brand 2"));
        Map<String, Object> pair = login(seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec2@a.com", "+919310000002", false));
        assertThat(transition(clinic.id(), "offboarding").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transition(clinic.id(), "offboarded").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/queue", (String) pair.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> r = refresh((String) pair.get("refreshToken"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("TENANT_INACTIVE");
        ResponseEntity<Map> sel = select(owner, clinic);
        assertThat(sel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(sel.getBody().get("type"))).endsWith("TENANT_INACTIVE");
    }

    @Test
    void ac2_boundaryTrialingAndOverdueClinicsStillRefresh() {
        for (String status : List.of("trialing", "overdue")) {
            SeededTenant clinic = seedTenant();
            Map<String, Object> pair = login(seedStaff(clinic, receptionistRole(clinic.id()), "qa2-" + status + "@a.com", "+91931000001" + status.length(), false));
            su.update("UPDATE tenants SET status = ? WHERE id = ?", status, clinic.id());
            assertThat(refresh((String) pair.get("refreshToken")).getStatusCode()).as(status).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void ac2_abuseClinicSuspendedOutsideTheLifecycleEndpointStillRefusesRefresh() {
        SeededTenant clinic = seedTenant();
        Map<String, Object> pair = login(seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec3@a.com", "+919310000003", false));
        su.update("UPDATE tenants SET status = 'suspended' WHERE id = ?", clinic.id()); // e.g. a billing job, no revoke ran
        ResponseEntity<Map> r = refresh((String) pair.get("refreshToken"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("TENANT_INACTIVE");
    }

    @Test
    void ac2_reactivationDoesNotResurrectOldSessions() {
        SeededTenant clinic = seedTenant();
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec4@a.com", "+919310000004", false);
        Map<String, Object> pair = login(rec);
        transition(clinic.id(), "suspended");
        assertThat(transition(clinic.id(), "active").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/queue", (String) pair.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh((String) pair.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/queue", (String) login(rec).get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================= AC3: role / scope / field-grant change =================

    @Test
    void ac3_roleChangeEndsEveryTabOfTheTargetOnlyAndTheNextSignInHasTheNewPermissions() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "qa2-own3@a.com", "+919310000020", true);
        String ownerAccess = mfaLogin(clinic, owner);
        UUID recRole = receptionistRole(clinic.id());
        SeededStaff rec = seedStaff(clinic, recRole, "qa2-rec20@a.com", "+919310000021", false);
        SeededStaff colleague = seedStaff(clinic, recRole, "qa2-rec21@a.com", "+919310000022", false);
        Map<String, Object> tab1 = login(rec), tab2 = login(rec), col = login(colleague);
        assertThat(get("/v1/staff", (String) tab1.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID newRole = seedRole(clinic.id(), "QA2 desk + staff view", false,
                new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("staff", true, false, false, false, false, false, false));

        assertThat(patchStaff(ownerAccess, rec.id(), Map.of("roleId", newRole.toString())).getStatusCode()).isEqualTo(HttpStatus.OK);

        for (Map<String, Object> tab : List.of(tab1, tab2)) {
            assertThat(get("/v1/queue", (String) tab.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refresh((String) tab.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(get("/v1/queue", (String) col.get("accessToken")).getStatusCode()).as("colleague on the old role").isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/staff", ownerAccess).getStatusCode()).as("owner's own session").isEqualTo(HttpStatus.OK);
        String fresh = (String) login(rec).get("accessToken");
        assertThat(get("/v1/staff", fresh).getStatusCode()).as("new permission works after sign-in").isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac3_fieldGrantChangeAlsoSignsOut() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "qa2-own4@a.com", "+919310000023", true);
        String ownerAccess = mfaLogin(clinic, owner);
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec22@a.com", "+919310000024", false);
        String a = (String) login(rec).get("accessToken");
        assertThat(patchStaff(ownerAccess, rec.id(), Map.of("fieldGrants", List.of("nric"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/queue", a).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Interpretation of AC3 "changes a receptionist's role": editing the role's grants (PATCH /v1/roles/{id}) changes the
     * receptionist's permissions just as much as re-assigning a role. Expected: within 60 s the removed permission no
     * longer works with the token issued before the edit.
     */
    @Test
    void ac3_editingTheGrantsOfTheRoleTheReceptionistHoldsStopsTheRemovedPermissionWithin60s() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "qa2-own5@a.com", "+919310000025", true);
        String ownerAccess = mfaLogin(clinic, owner);
        UUID role = seedRole(clinic.id(), "QA2 editable", false,
                new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("staff", true, false, false, false, false, false, false));
        SeededStaff rec = seedStaff(clinic, role, "qa2-rec23@a.com", "+919310000026", false);
        String before = (String) login(rec).get("accessToken");
        assertThat(get("/v1/staff", before).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> edit = exchange("/v1/roles/" + role, HttpMethod.PATCH, authedJsonBody(ownerAccess, Map.of(
                "name", "QA2 editable", "grants", List.of(new ModuleGrant("queue", true, true, true, false, false, false, false)))), Map.class);
        assertThat(edit.getStatusCode()).as(String.valueOf(edit.getBody())).isEqualTo(HttpStatus.OK);

        assertThat(get("/v1/staff", before).getStatusCode()).as("staff:view was removed from the role")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void ac3_isolationOwnerOfAnotherClinicCannotPatchOrSuspendThisClinicsStaff() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        SeededStaff ownerA = seedStaff(a, seedFullAccessRole(a.id()), "qa2-ownA@a.com", "+919310000027", true);
        String ownerAccess = mfaLogin(a, ownerA);
        SeededStaff recB = seedStaff(b, receptionistRole(b.id()), "qa2-recB@a.com", "+919310000028", false);
        String bAccess = (String) login(recB).get("accessToken");
        ResponseEntity<Map> p = patchStaff(ownerAccess, recB.id(), Map.of("scope", "own_patients_only"));
        assertThat(p.getStatusCode().is2xxSuccessful()).as("cross-tenant patch: %s", p.getStatusCode()).isFalse();
        ResponseEntity<Map> s = suspend(ownerAccess, recB.id(), stepUp(ownerAccess, ownerA));
        assertThat(s.getStatusCode().is2xxSuccessful()).as("cross-tenant suspend: %s", s.getStatusCode()).isFalse();
        assertThat(get("/v1/queue", bAccess).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, recB.id())).isEqualTo("active");
    }

    // ================= AC4: 12-hour refresh-family cap =================

    @Test
    void ac4_capHoldsAcrossManyRotationsAndUnlockAndKillsTheLastAccessTokenToo() {
        SeededTenant clinic = seedTenant();
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec30@a.com", "+919310000030", false);
        Map<String, Object> p = login(rec);
        String firstRefresh = (String) p.get("refreshToken");
        for (int i = 0; i < 3; i++) {
            p = refresh((String) p.get("refreshToken")).getBody();
        }
        backdate(rec.id(), "11 hours 59 minutes");
        p = refresh((String) p.get("refreshToken")).getBody();
        assertThat(p).as("11 h 59 m: still inside the cap").containsKey("accessToken");
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, authedJsonBody((String) p.get("accessToken"), Map.of("pin", STAFF_PIN)), Void.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
        backdate(rec.id(), "12 hours 1 minute");

        ResponseEntity<Map> refused = refresh((String) p.get("refreshToken"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(refused.getBody().get("type"))).endsWith("SESSION_MAX_AGE");
        assertThat(refused.getBody()).doesNotContainKey("accessToken");
        assertThat(get("/v1/queue", (String) p.get("accessToken")).getStatusCode()).as("the capped session's access token").isEqualTo(HttpStatus.UNAUTHORIZED);
        // replaying an old rotated token never yields a fresh pair
        ResponseEntity<Map> replay = refresh(firstRefresh);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).doesNotContainKey("accessToken");
    }

    @Test
    void ac4_capIsPerFamilyAnotherDeviceOfTheSameUserIsNotCut() {
        SeededTenant clinic = seedTenant();
        SeededStaff rec = seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec31@a.com", "+919310000031", false);
        Map<String, Object> oldDevice = login(rec);
        Map<String, Object> newDevice = login(rec);
        su.update("UPDATE sessions SET family_started_at = now() - interval '12 hours 1 minute' WHERE family_id = "
                + "(SELECT family_id FROM sessions WHERE id = ?::uuid)", claim((String) oldDevice.get("accessToken"), "sid"));
        assertThat(String.valueOf(refresh((String) oldDevice.get("refreshToken")).getBody().get("type"))).endsWith("SESSION_MAX_AGE");
        assertThat(refresh((String) newDevice.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================= AC5: step-up bound to its session =================

    @Test
    void ac5_stepUpFromSessionAWithSessionBIs403StepUpInvalidAndNothingHappens() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "qa2-own6@a.com", "+919310000040", true);
        SeededStaff target = seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec40@a.com", "+919310000041", false);
        String a = mfaLogin(clinic, owner);
        String b = mfaLogin(clinic, owner);
        String stepUpA = stepUp(a, owner);
        ResponseEntity<Map> r = suspend(b, target.id(), stepUpA);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("STEP_UP_INVALID");
        assertThat(su.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, target.id())).isEqualTo("active");
        // after session A logs out, its step-up is still useless with session B
        exchange("/v1/auth/logout", HttpMethod.POST, authed(a), Void.class);
        assertThat(suspend(b, target.id(), stepUpA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, target.id())).isEqualTo("active");
    }

    @Test
    void ac5_abuseSignedStepUpWithoutSidIsRefused() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "qa2-own7@a.com", "+919310000042", true);
        SeededStaff target = seedStaff(clinic, receptionistRole(clinic.id()), "qa2-rec42@a.com", "+919310000043", false);
        String access = mfaLogin(clinic, owner);
        String legacy = mintStepUp(owner.id(), null, null); // pre-PLT-007 shape: purpose + sub only
        ResponseEntity<Map> r = suspend(access, target.id(), legacy);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String wrongTenant = mintStepUp(owner.id(), claim(access, "sid"), UUID.randomUUID().toString());
        assertThat(suspend(access, target.id(), wrongTenant).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, target.id())).isEqualTo("active");
        // control: same encoder, right sid + tenant works (so the refusals above are about binding, not the minting)
        String good = mintStepUp(owner.id(), claim(access, "sid"), clinic.id().toString());
        assertThat(suspend(access, target.id(), good).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void ac5_abuseStepUpOfAnotherTenantsOwnerIsRefusedAndAStepUpIsNoAccessToken() {
        SeededTenant x = seedTenant();
        SeededTenant y = seedTenant();
        SeededStaff ownerX = seedStaff(x, seedFullAccessRole(x.id()), "qa2-ownX@a.com", "+919310000044", true);
        SeededStaff ownerY = seedStaff(y, seedFullAccessRole(y.id()), "qa2-ownY@a.com", "+919310000045", true);
        SeededStaff targetY = seedStaff(y, receptionistRole(y.id()), "qa2-recY@a.com", "+919310000046", false);
        String stepUpX = stepUp(mfaLogin(x, ownerX), ownerX);
        String yAccess = mfaLogin(y, ownerY);
        assertThat(suspend(yAccess, targetY.id(), stepUpX).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/queue", stepUpX).getStatusCode()).as("step-up used as bearer").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(su.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, targetY.id())).isEqualTo("active");
    }

    // ================= AC6: one-tap staff suspension ends all devices =================

    @Test
    void ac6_oneTapSuspensionEndsAllDevicesColleaguesKeepWorking() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "qa2-own8@a.com", "+919310000050", true);
        String ownerAccess = mfaLogin(clinic, owner);
        UUID recRole = receptionistRole(clinic.id());
        SeededStaff rec = seedStaff(clinic, recRole, "qa2-rec50@a.com", "+919310000051", false);
        SeededStaff colleague = seedStaff(clinic, recRole, "qa2-rec51@a.com", "+919310000052", false);
        List<Map<String, Object>> devices = List.of(login(rec), login(rec), login(rec), login(rec));
        String col = (String) login(colleague).get("accessToken");

        assertThat(suspend(ownerAccess, rec.id(), stepUp(ownerAccess, owner)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        for (Map<String, Object> d : devices) {
            assertThat(get("/v1/queue", (String) d.get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refresh((String) d.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(rawLogin(rec).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/queue", col).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac6_negativeSuspendWithoutStepUpOrByAReceptionistChangesNothing() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "qa2-own9@a.com", "+919310000053", true);
        String ownerAccess = mfaLogin(clinic, owner);
        UUID recRole = receptionistRole(clinic.id());
        SeededStaff rec = seedStaff(clinic, recRole, "qa2-rec53@a.com", "+919310000054", false);
        SeededStaff peer = seedStaff(clinic, recRole, "qa2-rec54@a.com", "+919310000055", false);
        String recAccess = (String) login(rec).get("accessToken");
        String peerAccess = (String) login(peer).get("accessToken");

        assertThat(suspend(ownerAccess, rec.id(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suspend(peerAccess, rec.id(), "garbage").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/queue", recAccess).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, rec.id())).isEqualTo("active");
    }

    // ================= helpers =================

    private UUID receptionistRole(UUID tenantId) {
        return seedRole(tenantId, "QA2 Receptionist " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("queue", true, true, true, false, false, false, false));
    }

    private ResponseEntity<Map> rawLogin(SeededStaff s) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
    }

    private Map<String, Object> login(SeededStaff s) {
        ResponseEntity<Map> r = rawLogin(s);
        assertThat(r.getStatusCode()).as("login %s: %s", s.email(), r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private Map<String, Object> platformLogin(SeededOperator op) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** Each account may use each TOTP time step once (PLT-003): walk -1, 0, +1 per account. */
    private String totp(UUID account) {
        int n = totpUses.merge(account, 1, Integer::sum);
        assertThat(n).as("TOTP uses for one account in one test").isLessThanOrEqualTo(3);
        return totpCodeAtOffset(n - 2);
    }

    private String mfaLogin(SeededTenant tenant, SeededStaff staff) {
        ResponseEntity<Map> l = rawLogin(staff);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", l.getBody().get("challengeId"), "code", totp(staff.id()))), Map.class);
        assertThat(v.getStatusCode()).as("mfa verify: %s", v.getBody()).isEqualTo(HttpStatus.OK);
        return (String) v.getBody().get("accessToken");
    }

    private String stepUp(String access, SeededStaff staff) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/mfa/verify"),
                new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + totp(staff.id()) + "\"}", h), Map.class);
        assertThat(r.getStatusCode()).as("step-up: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("stepUpToken");
    }

    private String mintStepUp(UUID staffId, String sid, String tenantId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject(staffId.toString()).claim("purpose", "step_up");
        if (sid != null) b.claim("sid", sid);
        if (tenantId != null) b.claim("tenantId", tenantId);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return jwtEncoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> suspend(String access, UUID staffId, String stepUp) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        if (stepUp != null) h.set("X-Step-Up-Token", stepUp);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(url("/v1/staff/" + staffId + "/suspend"), HttpMethod.POST, new HttpEntity<>(h), Map.class);
    }

    private ResponseEntity<Map> patchStaff(String access, UUID staffId, Map<String, Object> body) {
        return exchange("/v1/staff/" + staffId, HttpMethod.PATCH, authedJsonBody(access, body), Map.class);
    }

    private ResponseEntity<Map> select(SeededOwner owner, SeededTenant clinic) {
        return exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(ownerLoginPendingToken(owner), Map.of("clinicId", clinic.id().toString())), Map.class);
    }

    private ResponseEntity<Map> refresh(String refreshToken) {
        return http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", refreshToken)), Map.class);
    }

    private ResponseEntity<String> get(String path, String access) {
        return exchange(path, HttpMethod.GET, authed(access), String.class);
    }

    private ResponseEntity<Map> transition(UUID tenantId, String to) {
        SeededOperator op = seedOperator("qa2-sa-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        return exchange("/v1/platform/tenants/" + tenantId + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(platformLoginAndGetAccessToken(op), Map.of("toStatus", to, "reason", "qa2 plt-007")), Map.class);
    }

    private int liveSessions(UUID tenantId) {
        return su.queryForObject("SELECT count(*) FROM sessions WHERE tenant_id = ? AND revoked_at IS NULL", Integer.class, tenantId);
    }

    private void backdate(UUID staffId, String age) {
        su.update("UPDATE sessions SET family_started_at = now() - ?::interval WHERE staff_id = ?", age, staffId);
    }

    private String claim(String token, String name) {
        try {
            Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
            return String.valueOf(c.get(name));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
