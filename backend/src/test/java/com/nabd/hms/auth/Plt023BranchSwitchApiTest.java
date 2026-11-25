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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLT-023: one login and a branch switcher. A branch is a clinic (tenant): the session issued is always for one branch, the
 * link needs the brand's owner with a bound, single-use step-up and is audited in every branch named, suspension in one branch
 * leaves the other open, a single-branch user sees no choice, and the 'All branches (reports)' session answers 403 outside
 * reports. Negative paths: another brand's clinic, a forged link, a suspended row, a replayed brand_select token, the access
 * and refresh tokens of the branch that was left, a step-up from another branch's session, and the chosen branch's MFA rule.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Plt023BranchSwitchApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;

    /** One owner, one brand, branches A and B, and one doctor with a staff row (same email) in each. */
    private record Chain(SeededOwner owner, SeededBrand brand, SeededTenant a, SeededTenant b, SeededStaff docA, SeededStaff docB) {
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String phone() {
        return "+9198" + String.format("%08d", (long) (Math.random() * 99_999_999L));
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private UUID doctorRole(UUID tenant) {
        return seedRole(tenant, "Doctor " + tag(), false, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"), fullGrant("reports"));
    }

    private Chain chain(boolean mfaInB) {
        SeededOwner owner = seedOwner("owner-" + tag() + "@plt023.test");
        SeededBrand brand = seedBrand(owner, "Brand " + tag());
        SeededTenant a = seedClinicInBrand(brand), b = seedClinicInBrand(brand);
        String email = "doc-" + tag() + "@plt023.test";
        return new Chain(owner, brand, a, b, seedStaff(a, doctorRole(a.id()), email, phone(), false),
                seedStaff(b, doctorRole(b.id()), email, phone(), mfaInB));
    }

    /** The owner signed in to one of their clinics through the owner portal (the brand's owner account). */
    private String ownerIn(SeededOwner owner, UUID clinicId) {
        ResponseEntity<Map> r = exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(ownerLoginPendingToken(owner), Map.of("clinicId", clinicId.toString())), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    private Map<String, Object> claims(String jwt) {
        return claimsOf(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8));
    }

    private Map<String, Object> claimsOf(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String linkTarget(UUID... ids) {
        return "staff-link:" + List.of(ids).stream().sorted().map(UUID::toString).collect(Collectors.joining(","));
    }

    /** What /v1/auth/mfa/verify mints for an authenticated caller who asked for {@code target}. */
    private String stepUp(String access, String target) {
        Map<String, Object> c = claims(access);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString()).subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid"))
                .claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> link(String token, String stepUp, UUID... staffIds) {
        return exchange("/v1/owner/branches/staff-links", HttpMethod.POST, authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp,
                Map.of("staffIds", List.of(staffIds).stream().map(UUID::toString).toList())), Map.class);
    }

    /** The owner links the doctor's two rows; returns the brand login id. */
    private UUID linked(Chain c) {
        String owner = ownerIn(c.owner(), c.a().id());
        ResponseEntity<Map> r = link(owner, stepUp(owner, linkTarget(c.docA().id(), c.docB().id())), c.docA().id(), c.docB().id());
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("brandLoginId"));
    }

    private ResponseEntity<Map> login(SeededStaff s) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
    }

    /** PIN sign-in of a linked person: the branch choice token (no session yet). */
    private String choiceToken(SeededStaff s) {
        ResponseEntity<Map> r = login(s);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("branchSelectToken").doesNotContainKeys("accessToken", "refreshToken");
        return (String) r.getBody().get("branchSelectToken");
    }

    private ResponseEntity<Map> choose(String choiceToken, UUID tenantId) {
        return http.postForEntity(url("/v1/auth/branches/select"), jsonBody(Map.of("tenantId", tenantId.toString(), "branchSelectToken", choiceToken)), Map.class);
    }

    private ResponseEntity<Map> switchTo(String accessToken, UUID tenantId, boolean reportsOnly) {
        Map<String, Object> body = new HashMap<>(Map.of("tenantId", tenantId.toString()));
        if (reportsOnly) body.put("reportsOnly", true);
        return exchange("/v1/auth/branches/select", HttpMethod.POST, authedJsonBody(accessToken, body), Map.class);
    }

    private String access(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    private int get(String token, String path) {
        return exchange(path, HttpMethod.GET, authed(token), String.class).getStatusCode().value();
    }

    private long audits(UUID tenant, String action) {
        return inTenantTx(tenant, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Long.class, tenant, action));
    }

    private List<String> branchIds(String token) {
        ResponseEntity<Map> r = exchange("/v1/auth/branches", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody().get("branches")).stream().map(b -> (String) b.get("tenantId")).toList();
    }

    // ---- AC5: the link -------------------------------------------------------------------------------------------------

    @Test
    void linkingNeedsTheBrandOwnerWithABoundStepUpAndIsAuditedInBothBranches() {
        Chain c = chain(false);
        String owner = ownerIn(c.owner(), c.a().id());
        UUID a = c.docA().id(), b = c.docB().id();

        // no confirmation, and a confirmation for other staff rows
        assertThat(link(owner, null, a, b).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(link(owner, stepUp(owner, linkTarget(a, UUID.randomUUID())), a, b).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // a staff member on the built-in Owner role who is not the brand's owner account: refused on the server
        SeededStaff manager = seedStaff(c.a(), c.docA().roleId(), "mgr-" + tag() + "@plt023.test", phone(), false);
        UUID ownerRole = inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT id FROM roles WHERE tenant_id = ? AND built_in", UUID.class, c.a().id()));
        inTenantTx(c.a().id(), () -> jdbc.update("UPDATE staff SET role_id = ? WHERE id = ?", ownerRole, manager.id()));
        String mgr = loginAndGetAccessToken(manager);
        ResponseEntity<Map> notOwner = link(mgr, stepUp(mgr, linkTarget(a, b)), a, b);
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(notOwner.getBody())).contains("OWNER_ONLY");
        // a role without staff:edit never reaches the service
        String doc = loginAndGetAccessToken(c.docA());
        assertThat(link(doc, stepUp(doc, linkTarget(a, b)), a, b).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT brand_login_id FROM staff WHERE id = ?", UUID.class, a))).isNull();
        assertThat(audits(c.a().id(), "staff.branch_linked")).isZero();

        // the owner's confirmation names exactly these rows
        String su = stepUp(owner, linkTarget(a, b));
        ResponseEntity<Map> ok = link(owner, su, a, b);
        assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID login = UUID.fromString((String) ok.getBody().get("brandLoginId"));
        assertThat(inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT brand_login_id FROM staff WHERE id = ?", UUID.class, a))).isEqualTo(login);
        assertThat(inTenantTx(c.b().id(), () -> jdbc.queryForObject("SELECT brand_login_id FROM staff WHERE id = ?", UUID.class, b))).isEqualTo(login);
        assertThat(su().queryForObject("SELECT brand_id FROM brand_logins WHERE id = ?", UUID.class, login)).isEqualTo(c.brand().id());

        // audited in both branches, on each branch's own chain, which still verifies
        for (SeededTenant t : List.of(c.a(), c.b())) {
            assertThat(audits(t.id(), "staff.branch_linked")).as("audit in " + t.slug()).isEqualTo(1);
            assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT after ->> 'brandLoginId' FROM audit_log WHERE tenant_id = ? AND action = 'staff.branch_linked'",
                    String.class, t.id()))).isEqualTo(login.toString());
            assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t.id()))).isNull();
        }

        // the same confirmation again, and a fresh one for rows already linked: nothing new
        assertThat(link(owner, su, a, b).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> again = link(owner, stepUp(owner, linkTarget(a, b)), a, b);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(again.getBody())).contains("ALREADY_LINKED");
        assertThat(su().queryForObject("SELECT count(*) FROM brand_logins WHERE brand_id = ?", Long.class, c.brand().id())).isEqualTo(1);
        assertThat(audits(c.b().id(), "staff.branch_linked")).isEqualTo(1);
    }

    @Test
    void linkRefusesOtherBrandsOtherPeopleSuspendedRowsAndTwoRowsOfOneBranch() {
        Chain c = chain(false);
        String owner = ownerIn(c.owner(), c.a().id());
        UUID a = c.docA().id();

        // a clinic of another brand (another owner), same email: answers like an unknown staff id
        SeededTenant foreign = seedClinicInBrand(seedBrand(seedOwner("other-" + tag() + "@plt023.test"), "Other " + tag()));
        SeededStaff foreignDoc = seedStaff(foreign, doctorRole(foreign.id()), c.docA().email(), phone(), false);
        assertThat(link(owner, stepUp(owner, linkTarget(a, foreignDoc.id())), a, foreignDoc.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        UUID ghost = UUID.randomUUID();
        assertThat(link(owner, stepUp(owner, linkTarget(a, ghost)), a, ghost).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // a different person in the other branch (no shared email or mobile)
        SeededStaff nurse = seedStaff(c.b(), c.docB().roleId(), "nurse-" + tag() + "@plt023.test", phone(), false);
        ResponseEntity<Map> mismatch = link(owner, stepUp(owner, linkTarget(a, nurse.id())), a, nurse.id());
        assertThat(mismatch.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(mismatch.getBody())).contains("IDENTITY_MISMATCH");

        // two rows of the same branch
        SeededStaff second = seedStaff(c.a(), c.docA().roleId(), "second-" + tag() + "@plt023.test", phone(), false);
        assertThat(link(owner, stepUp(owner, linkTarget(a, second.id())), a, second.id()).getStatusCode().value()).isEqualTo(422);

        // a suspended row is not linked
        inTenantTx(c.b().id(), () -> jdbc.update("UPDATE staff SET status = 'suspended' WHERE id = ?", c.docB().id()));
        ResponseEntity<Map> suspended = link(owner, stepUp(owner, linkTarget(a, c.docB().id())), a, c.docB().id());
        assertThat(suspended.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(suspended.getBody())).contains("NOT_ELIGIBLE");

        assertThat(su().queryForObject("SELECT count(*) FROM brand_logins WHERE brand_id = ?", Long.class, c.brand().id())).isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM staff WHERE brand_login_id IS NOT NULL AND tenant_id IN (?, ?, ?)", Long.class,
                c.a().id(), c.b().id(), foreign.id())).isZero();
    }

    @Test
    void stepUpFromAnotherBranchSessionDoesNotApproveALink() {
        Chain c = chain(false);
        String ownerA = ownerIn(c.owner(), c.a().id()), ownerB = ownerIn(c.owner(), c.b().id());
        ResponseEntity<Map> r = link(ownerA, stepUp(ownerB, linkTarget(c.docA().id(), c.docB().id())), c.docA().id(), c.docB().id());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su().queryForObject("SELECT count(*) FROM brand_logins WHERE brand_id = ?", Long.class, c.brand().id())).isZero();
    }

    @Test
    void candidatesAreTheSamePersonInTheOwnersOtherBranchesOnly() {
        Chain c = chain(false);
        SeededTenant foreign = seedClinicInBrand(seedBrand(seedOwner("other-" + tag() + "@plt023.test"), "Other " + tag()));
        seedStaff(foreign, doctorRole(foreign.id()), c.docA().email(), phone(), false);
        seedStaff(c.b(), c.docB().roleId(), "someone-" + tag() + "@plt023.test", phone(), false);

        String owner = ownerIn(c.owner(), c.a().id());
        ResponseEntity<List> mine = exchange("/v1/owner/branches/staff-links/candidates?staffIdToLink=" + c.docA().id(), HttpMethod.GET, authed(owner), List.class);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) mine.getBody()).singleElement().satisfies(m -> assertThat(m)
                .containsEntry("staffId", c.docB().id().toString()).containsEntry("tenantId", c.b().id().toString()).containsEntry("linked", false));

        // a manager with staff:edit who is not the brand's owner account learns nothing about the other branch
        SeededStaff manager = seedStaff(c.a(), seedRole(c.a().id(), "Manager " + tag(), false, fullGrant("staff")), "mgr-" + tag() + "@plt023.test", phone(), false);
        ResponseEntity<List> none = exchange("/v1/owner/branches/staff-links/candidates?staffIdToLink=" + c.docA().id(), HttpMethod.GET,
                authed(loginAndGetAccessToken(manager)), List.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(none.getBody()).isEmpty();
    }

    @Test
    void theAppRoleCannotReadBrandLoginsOrForgeALink() {
        Chain c = chain(false);
        UUID login = linked(c);
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM brand_logins", Long.class)).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM brand_login_token_uses", Long.class)).hasMessageContaining("permission denied");
        SeededStaff other = seedStaff(c.a(), c.docA().roleId(), "forge-" + tag() + "@plt023.test", phone(), false);
        assertThatThrownBy(() -> inTenantTx(c.a().id(), () -> jdbc.update("UPDATE staff SET brand_login_id = ? WHERE id = ?", login, other.id())))
                .hasMessageContaining("link_brand_staff");
        assertThat(inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT brand_login_id FROM staff WHERE id = ?", UUID.class, other.id()))).isNull();
    }

    // ---- AC1, AC4: sign in once, pick a branch; no choice for a single-branch user -------------------------------------------

    @Test
    void signInOnceOffersTheBranchesAndTheSessionIsForTheChosenBranchOnly() {
        Chain c = chain(false);
        UUID login = linked(c);

        ResponseEntity<Map> signIn = login(c.docA());
        assertThat(signIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signIn.getBody()).containsKey("branchSelectToken").doesNotContainKeys("accessToken", "refreshToken");
        assertThat(((List<Map<String, Object>>) signIn.getBody().get("branches")).stream().map(b -> b.get("tenantId")))
                .containsExactlyInAnyOrder(c.a().id().toString(), c.b().id().toString());
        String choice = (String) signIn.getBody().get("branchSelectToken");
        assertThat(claims(choice)).containsEntry("typ", "brand_select").containsEntry("tenantId", c.a().id().toString()).doesNotContainKey("permissions");
        // the choice token is not a session: no endpoint takes it as a bearer
        assertThat(get(choice, "/v1/auth/branches")).isEqualTo(401);
        assertThat(get(choice, "/v1/patients")).isEqualTo(401);

        // signed in at A, enters B: the token is B's, for B's staff row, and names no other tenant
        ResponseEntity<Map> chosen = choose(choice, c.b().id());
        String token = access(chosen);
        Map<String, Object> cl = claims(token);
        assertThat(cl).containsEntry("tenantId", c.b().id().toString()).containsEntry("sub", c.docB().id().toString())
                .containsEntry("typ", "access").containsEntry("brandLogin", login.toString()).doesNotContainKey("reportsOnly");
        assertThat(objectMapper.valueToTree(cl).toString()).doesNotContain(c.a().id().toString()).doesNotContain(c.docA().id().toString());
        assertThat(get(token, "/v1/patients")).isEqualTo(200);
        assertThat(branchIds(token)).containsExactlyInAnyOrder(c.a().id().toString(), c.b().id().toString());
        // refresh stays in B
        ResponseEntity<Map> refreshed = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", chosen.getBody().get("refreshToken"))), Map.class);
        assertThat(claims(access(refreshed))).containsEntry("tenantId", c.b().id().toString()).containsEntry("sub", c.docB().id().toString());

        // replay of the used choice token, for either branch
        assertThat(choose(choice, c.b().id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(choose(choice, c.a().id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // no token, a garbage token, another token type in its place
        assertThat(http.postForEntity(url("/v1/auth/branches/select"), jsonBody(Map.of("tenantId", c.b().id().toString())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(choose("not-a-token", c.b().id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(choose(token, c.a().id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(audits(c.b().id(), "auth.branch_selected")).isEqualTo(1);
        Map<String, Object> data = (Map<String, Object>) claimsOf(su().queryForObject("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? "
                + "AND envelope ->> 'type' = 'iam.branch.switched.v1'", String.class, c.b().id())).get("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("tenantId", "brandLoginId", "fromTenantId", "toTenantId", "status", "version");
        assertThat(data).containsEntry("brandLoginId", login.toString()).containsEntry("fromTenantId", c.a().id().toString())
                .containsEntry("toTenantId", c.b().id().toString()).containsEntry("tenantId", c.b().id().toString());
    }

    @Test
    void aSingleBranchUserGetsTheSessionAtOnceAndNoBranchList() {
        Chain c = chain(false);
        linked(c);
        // a receptionist with one staff row: sign-in answers as it always did
        SeededStaff reception = seedStaff(c.a(), seedRole(c.a().id(), "Reception " + tag(), false, fullGrant("queue"), fullGrant("patients")),
                "desk-" + tag() + "@plt023.test", phone(), false);
        ResponseEntity<Map> r = login(reception);
        assertThat(r.getBody()).containsKeys("accessToken", "refreshToken").doesNotContainKey("branchSelectToken");
        String token = (String) r.getBody().get("accessToken");
        assertThat(claims(token)).doesNotContainKeys("brandLogin", "reportsOnly");
        assertThat(branchIds(token)).isEmpty();
        // and cannot enter the brand's other branch, nor re-enter their own through the switch
        assertThat(switchTo(token, c.b().id(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(switchTo(token, c.b().id(), false).getBody())).contains("NOT_LINKED");
        assertThat(get(token, "/v1/patients")).as("a refused switch leaves the session alone").isEqualTo(200);
    }

    // ---- AC2: switch ------------------------------------------------------------------------------------------------------

    @Test
    void switchingIssuesASessionForTheOtherBranchAndEndsTheOneLeftBehind() {
        Chain c = chain(false);
        linked(c);
        ResponseEntity<Map> inA = choose(choiceToken(c.docA()), c.a().id());
        String tokenA = access(inA);
        assertThat(claims(tokenA)).containsEntry("tenantId", c.a().id().toString()).containsEntry("sub", c.docA().id().toString());

        long t0 = System.nanoTime();
        ResponseEntity<Map> inB = switchTo(tokenA, c.b().id(), false);
        long firstMs = (System.nanoTime() - t0) / 1_000_000;
        String tokenB = access(inB);
        assertThat(claims(tokenB)).containsEntry("tenantId", c.b().id().toString()).containsEntry("sub", c.docB().id().toString());
        assertThat(objectMapper.valueToTree(claims(tokenB)).toString()).doesNotContain(c.a().id().toString());
        assertThat(get(tokenB, "/v1/patients")).isEqualTo(200);

        // the session that was left: its access token cannot switch again, its refresh token is dead, and neither opens B
        assertThat(switchTo(tokenA, c.b().id(), false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(switchTo(tokenA, c.a().id(), false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", inA.getBody().get("refreshToken"))), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_at IS NULL", Long.class, c.docA().id()))).isZero();
        assertThat(audits(c.a().id(), "auth.branch_left")).isEqualTo(1);
        assertThat(audits(c.b().id(), "auth.branch_selected")).isEqualTo(1);

        // back and forth: each switch well under a second (NFR: p95 < 1 s; the first call above also warms the path)
        List<Long> took = new java.util.ArrayList<>();
        String current = tokenB;
        for (UUID target : List.of(c.a().id(), c.b().id(), c.a().id(), c.b().id(), c.a().id())) {
            long s = System.nanoTime();
            ResponseEntity<Map> r = switchTo(current, target, false);
            took.add((System.nanoTime() - s) / 1_000_000);
            current = access(r);
            assertThat(claims(current)).containsEntry("tenantId", target.toString());
        }
        // the median of five, so one stall of the shared test machine does not decide it
        assertThat(took.stream().sorted().toList().get(2)).as("median switch time in ms, all: " + took + ", first: " + firstMs).isLessThan(1000);
        // one live session in the branch the person is in, none in the other
        assertThat(inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_at IS NULL", Long.class, c.docA().id()))).isEqualTo(1);
        assertThat(inTenantTx(c.b().id(), () -> jdbc.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ? AND revoked_at IS NULL", Long.class, c.docB().id()))).isZero();
    }

    // ---- AC3: suspended in one branch only ------------------------------------------------------------------------------------

    @Test
    void suspendedInOneBranchStillSignsInToTheOtherButNotThatOne() {
        Chain c = chain(false);
        linked(c);
        String early = choiceToken(c.docB()); // issued while both branches were open
        inTenantTx(c.a().id(), () -> jdbc.update("UPDATE staff SET status = 'suspended' WHERE id = ?", c.docA().id()));

        assertThat(login(c.docA()).getStatusCode()).as("sign-in at the suspended branch").isEqualTo(HttpStatus.UNAUTHORIZED);
        // the choice made before the suspension: A is refused, B still opens with the same token
        ResponseEntity<Map> refused = choose(early, c.a().id());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(refused.getBody())).contains("NOT_LINKED");
        String tokenB = access(choose(early, c.b().id()));
        assertThat(claims(tokenB)).containsEntry("tenantId", c.b().id().toString());
        assertThat(get(tokenB, "/v1/patients")).isEqualTo(200);
        // from inside B: A is not offered and cannot be entered
        assertThat(branchIds(tokenB)).containsExactly(c.b().id().toString());
        assertThat(switchTo(tokenB, c.a().id(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(tokenB, "/v1/patients")).isEqualTo(200);
        // a fresh sign-in at B: one open branch, so the session at once
        assertThat(login(c.docB()).getBody()).containsKey("accessToken").doesNotContainKey("branchSelectToken");
        assertThat(inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ?", Long.class, c.docA().id()))).isZero();
    }

    // ---- another brand, a forged link, an offboarded branch ----------------------------------------------------------------------

    @Test
    void aBranchOfAnotherBrandCanNeverBeSelected() {
        Chain c = chain(false);
        UUID login = linked(c);
        SeededTenant foreign = seedClinicInBrand(seedBrand(seedOwner("other-" + tag() + "@plt023.test"), "Other " + tag()));
        SeededStaff foreignDoc = seedStaff(foreign, doctorRole(foreign.id()), c.docA().email(), phone(), false);

        String choice = choiceToken(c.docA());
        assertThat(choose(choice, foreign.id()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(choose(choice, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String tokenA = access(choose(choice, c.a().id()));
        assertThat(switchTo(tokenA, foreign.id(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // even a row of another brand that carries this login id (corrupted data, written as superuser) opens nothing
        su().update("UPDATE staff SET brand_login_id = ? WHERE id = ?", login, foreignDoc.id());
        ResponseEntity<Map> forged = switchTo(tokenA, foreign.id(), false);
        assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(forged.getBody())).contains("NOT_LINKED");
        assertThat(branchIds(tokenA)).containsExactlyInAnyOrder(c.a().id().toString(), c.b().id().toString());
        // and that foreign row gains no way into this brand
        String foreignToken = (String) login(foreignDoc).getBody().get("accessToken");
        assertThat(foreignToken).as("the foreign row signs in to its own clinic only, without a choice").isNotNull();
        assertThat(branchIds(foreignToken)).isEmpty();
        assertThat(switchTo(foreignToken, c.a().id(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inTenantTx(foreign.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'auth.branch_selected'",
                Long.class, foreign.id()))).isZero();
    }

    @Test
    void anOffboardedBranchAnswersTenantInactive() {
        Chain c = chain(false);
        linked(c);
        String tokenA = access(choose(choiceToken(c.docA()), c.a().id()));
        su().update("UPDATE tenants SET status = 'offboarded' WHERE id = ?", c.b().id());
        ResponseEntity<Map> r = switchTo(tokenA, c.b().id(), false);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody())).contains("TENANT_INACTIVE");
        assertThat(branchIds(tokenA)).containsExactly(c.a().id().toString());
        assertThat(get(tokenA, "/v1/patients")).isEqualTo(200);
    }

    // ---- the chosen branch's own MFA rule ------------------------------------------------------------------------------------

    @Test
    void aBranchWhereThePersonHasMfaNeedsASignInThatPassedIt() {
        Chain c = chain(true); // MFA on the row in B, none in A
        linked(c);

        // PIN only, at A: B is refused, A opens with the same choice token
        String pinOnly = choiceToken(c.docA());
        assertThat(claims(pinOnly)).containsEntry("mfa", false);
        ResponseEntity<Map> refused = choose(pinOnly, c.b().id());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(refused.getBody())).contains("MFA_REQUIRED");
        String tokenA = access(choose(pinOnly, c.a().id()));
        // the switch from that session is refused too, and the session survives the refusal
        assertThat(String.valueOf(switchTo(tokenA, c.b().id(), false).getBody())).contains("MFA_REQUIRED");
        assertThat(get(tokenA, "/v1/patients")).isEqualTo(200);
        assertThat(inTenantTx(c.b().id(), () -> jdbc.queryForObject("SELECT count(*) FROM sessions WHERE staff_id = ?", Long.class, c.docB().id()))).isZero();

        // PIN + authenticator code at B: the choice follows the second factor, and both branches open
        ResponseEntity<Map> first = login(c.docB());
        assertThat(first.getBody()).containsKey("challengeId").doesNotContainKey("branchSelectToken");
        ResponseEntity<Map> second = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", first.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        assertThat(second.getStatusCode()).as(String.valueOf(second.getBody())).isEqualTo(HttpStatus.OK);
        String withMfa = (String) second.getBody().get("branchSelectToken");
        assertThat(claims(withMfa)).containsEntry("mfa", true);
        String tokenB = access(choose(withMfa, c.b().id()));
        assertThat(claims(tokenB)).containsEntry("tenantId", c.b().id().toString());
        assertThat(claims(access(switchTo(tokenB, c.a().id(), false)))).containsEntry("tenantId", c.a().id().toString());
    }

    // ---- AC6 (server part), AC7: the owner switches; 'All branches (reports)' ---------------------------------------------------

    @Test
    void theOwnerSwitchesBetweenTheirBranchesOfOneBrandOnly() {
        Chain c = chain(false);
        SeededTenant otherBrandOfSameOwner = seedClinicInBrand(seedBrand(c.owner(), "Second " + tag()));
        SeededTenant foreign = seedClinicInBrand(seedBrand(seedOwner("other-" + tag() + "@plt023.test"), "Other " + tag()));
        String ownerA = ownerIn(c.owner(), c.a().id());
        assertThat(branchIds(ownerA)).containsExactlyInAnyOrder(c.a().id().toString(), c.b().id().toString());

        String ownerB = access(switchTo(ownerA, c.b().id(), false));
        Map<String, Object> cl = claims(ownerB);
        assertThat(cl).containsEntry("tenantId", c.b().id().toString()).containsEntry("owner", true);
        assertThat(inTenantTx(c.b().id(), () -> jdbc.queryForObject("SELECT owner_id FROM staff WHERE id = ?", UUID.class, UUID.fromString((String) cl.get("sub")))))
                .isEqualTo(c.owner().id());
        assertThat(get(ownerB, "/v1/patients")).isEqualTo(200);
        assertThat(switchTo(ownerA, c.b().id(), false).getStatusCode()).as("the session that was left").isEqualTo(HttpStatus.UNAUTHORIZED);
        // another brand (theirs or anyone's) is not a branch of this one
        assertThat(switchTo(ownerB, otherBrandOfSameOwner.id(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(switchTo(ownerB, foreign.id(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void allBranchesReportsModeOpensReportsAndAnswers403OnEverythingClinical() {
        Chain c = chain(false);
        linked(c);
        String tokenA = access(choose(choiceToken(c.docA()), c.a().id()));

        ResponseEntity<Map> mode = switchTo(tokenA, c.a().id(), true);
        String reports = access(mode);
        Map<String, Object> cl = claims(reports);
        assertThat(cl).containsEntry("reportsOnly", true).containsEntry("tenantId", c.a().id().toString()).doesNotContainKey("specialty");
        assertThat((List<String>) cl.get("permissions")).isNotEmpty().allMatch(p -> p.startsWith("reports:"));

        List<String[]> clinical = List.of(new String[]{"GET", "/v1/patients"}, new String[]{"GET", "/v1/queue"}, new String[]{"GET", "/v1/appointments"},
                new String[]{"GET", "/v1/clinical/notes/" + UUID.randomUUID()}, new String[]{"GET", "/v1/staff"}, new String[]{"GET", "/v1/nursing/administration-orders/today"},
                new String[]{"GET", "/v1/setup/profile"}, new String[]{"POST", "/v1/patients"}, new String[]{"POST", "/v1/auth/break-glass/activate"},
                new String[]{"GET", "/v1/owner/branches"});
        for (String[] ep : clinical) {
            ResponseEntity<String> r = exchange(ep[1], HttpMethod.valueOf(ep[0]), "GET".equals(ep[0]) ? authed(reports) : authedJsonBody(reports, Map.of()), String.class);
            assertThat(r.getStatusCode().value()).as(ep[0] + " " + ep[1] + " in reports-only mode: " + r.getBody()).isEqualTo(403);
            assertThat(r.getBody()).contains("REPORTS_ONLY");
        }
        assertThat(get(reports, "/v1/reports/overview")).isEqualTo(200);
        assertThat(get(reports, "/v1/auth/branches")).isEqualTo(200);
        assertThat(get(reports, "/v1/me/entitlements")).isEqualTo(200);

        // a refresh keeps the mode
        String refreshed = access(http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", mode.getBody().get("refreshToken"))), Map.class));
        assertThat(claims(refreshed)).containsEntry("reportsOnly", true);
        assertThat(get(refreshed, "/v1/patients")).isEqualTo(403);
        assertThat(get(refreshed, "/v1/reports/overview")).isEqualTo(200);

        // leaving the mode is a normal branch choice again
        String normal = access(switchTo(refreshed, c.b().id(), false));
        assertThat(claims(normal)).containsEntry("tenantId", c.b().id().toString()).doesNotContainKey("reportsOnly");
        assertThat(get(normal, "/v1/patients")).isEqualTo(200);

        // a linked person whose role in that branch has no reports cannot have the mode, and keeps the session they had
        Chain d = chain(false);
        UUID noReports = seedRole(d.a().id(), "Reception " + tag(), false, fullGrant("queue"), new ModuleGrant("patients", true, false, false, false, false, false, false));
        inTenantTx(d.a().id(), () -> jdbc.update("UPDATE staff SET role_id = ? WHERE id = ?", noReports, d.docA().id()));
        linked(d);
        String desk = access(choose(choiceToken(d.docA()), d.a().id()));
        ResponseEntity<Map> no = switchTo(desk, d.a().id(), true);
        assertThat(no.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(no.getBody())).contains("REPORTS_NOT_ALLOWED");
        assertThat(get(desk, "/v1/patients")).as("the refused request left the session alone").isEqualTo(200);
    }

    @Test
    void headersOfTheSelectEndpointNeverCarryMoreThanOneBranch() {
        // Authorization of one branch plus the choice token of a sign-in elsewhere: the access token decides, the body token is ignored
        Chain c = chain(false);
        linked(c);
        String tokenA = access(choose(choiceToken(c.docA()), c.a().id()));
        String choiceFromB = choiceToken(c.docB());
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(tokenA);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = exchange("/v1/auth/branches/select", HttpMethod.POST, new HttpEntity<>(
                "{\"tenantId\":\"" + c.b().id() + "\",\"branchSelectToken\":\"" + choiceFromB + "\"}", h), Map.class);
        assertThat(claims(access(r))).containsEntry("tenantId", c.b().id().toString());
        // the body token was not consumed by that call
        assertThat(claims(access(choose(choiceFromB, c.b().id())))).containsEntry("tenantId", c.b().id().toString());
    }
}
