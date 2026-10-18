package com.nabd.hms.staff;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S9 PLT-014): written from the story's acceptance criteria. */
class Plt014DelegationS9QaTest extends ApiTestBase {

    @Autowired
    private JwtEncoder jwtEncoder;

    private static ModuleGrant g(String m, boolean v, boolean c, boolean e, boolean d, boolean a) {
        return new ModuleGrant(m, v, c, e, d, a, false, false);
    }

    private ResponseEntity<Map> delegate(String token, UUID staffId, UUID roleId, Instant exp, String stepUp) {
        Map<String, String> body = Map.of("staffId", staffId.toString(), "delegatedRoleId", roleId.toString(), "reason", "cover", "expiresAt", exp.toString());
        HttpEntity<String> req = stepUp == null ? authedJsonBody(token, body) : authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp, body);
        return exchange("/v1/roles/delegations", HttpMethod.POST, req, Map.class);
    }

    private ResponseEntity<Map> delegate(String token, UUID staffId, UUID roleId) {
        return delegate(token, staffId, roleId, Instant.now().plusSeconds(3600), null);
    }

    private String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    private UUID managerRole(UUID tid) {
        return seedRole(tid, "Mgr" + UUID.randomUUID().toString().substring(0, 6), false, g("staff", true, true, true, false, false), g("clinical", true, false, false, false, false));
    }

    private String token(SeededStaff s) {
        return loginAndGetAccessToken(s);
    }

    private String mfaLogin(SeededTenant t, SeededStaff s) {
        String challengeId = (String) http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", t.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class)
                .getBody().get("challengeId");
        return (String) http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", challengeId, "code", currentTotpCode())), Map.class)
                .getBody().get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claims(String token) {
        try {
            return objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String stepUpWithPurpose(String access, String purpose, String subject) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject(subject != null ? subject : (String) claims(access).get("sub")).claim("purpose", purpose).claim("sid", claims(access).get("sid"));
        com.nabd.hms.shared.security.TokenType.STEP_UP.stamp(b, com.nabd.hms.shared.security.Audience.STEP_UP);
        return jwtEncoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private String realStepUp(String access) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return (String) http.postForEntity(url("/v1/auth/mfa/verify"), new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", h), Map.class)
                .getBody().get("stepUpToken");
    }

    // ---- AC1 self-delegation ----
    @Test
    void ac1_selfDelegationIs403SelfDelegation_forAnyRoleIncludingOwnerAndWithoutCreatingRows() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, ownerRole, "p14o@a.com", "+919400200001", true);
        SeededStaff mgr = seedStaff(t, managerRole(t.id()), "p14m@a.com", "+919400200002", false);
        UUID cover = seedRole(t.id(), "Cover", false, g("clinical", true, false, false, false, false));
        ResponseEntity<Map> r = delegate(token(mgr), mgr.id(), cover);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r)).endsWith("self-delegation");
        // even an Owner with a valid step-up cannot delegate to self
        String ot = mfaLogin(t, owner);
        ResponseEntity<Map> r2 = delegate(ot, owner.id(), ownerRole, Instant.now().plusSeconds(3600), realStepUp(ot));
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r2)).endsWith("self-delegation");
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM role_delegations", Integer.class))).isZero();
    }

    // ---- AC2 grantable set is base role only ----
    @Test
    void ac2_borrowedGrantsDoNotCountTowardsWhatMayBeDelegated() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "p14o2@a.com", "+919400200011", false);
        // base: staff:edit only. loan: billing. target role: a *different* role with the same billing grants.
        SeededStaff holder = seedStaff(t, seedRole(t.id(), "StaffOnly", false, g("staff", true, true, true, false, false)), "p14h@a.com", "+919400200012", false);
        SeededStaff target = seedStaff(t, managerRole(t.id()), "p14t@a.com", "+919400200013", false);
        UUID loan = seedRole(t.id(), "BillingLoan", false, g("billing", true, true, true, true, true));
        UUID twin = seedRole(t.id(), "BillingTwin", false, g("billing", true, true, true, true, true));
        UUID bigger = seedRole(t.id(), "Bigger", false, g("staff", true, true, true, true, true));
        assertThat(delegate(token(owner), holder.id(), loan).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ht = token(holder); // fresh session carries the borrowed billing:*
        assertThat((java.util.List<String>) claims(ht).get("permissions")).contains("billing:approve", "staff:edit");

        ResponseEntity<Map> twinResp = delegate(ht, target.id(), twin);
        assertThat(twinResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(twinResp)).endsWith("grant-exceeds-base-role");
        ResponseEntity<Map> biggerResp = delegate(ht, target.id(), bigger); // staff:delete/approve beyond base
        assertThat(biggerResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(biggerResp)).endsWith("grant-exceeds-base-role");
        // chained: the very role on loan
        ResponseEntity<Map> chained = delegate(ht, target.id(), loan);
        assertThat(chained.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(chained)).endsWith("chained-delegation");
        // within the base role (a subset of staff:edit) is allowed
        UUID subset = seedRole(t.id(), "Subset", false, g("staff", true, false, false, false, false));
        assertThat(delegate(ht, target.id(), subset).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM role_delegations WHERE staff_id = ?", Integer.class, target.id()))).isEqualTo(1);
    }

    @Test
    void ac2_requestedRoleMayNotExceedBaseEvenWithoutAnyLoan() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = seedStaff(t, managerRole(t.id()), "p14x@a.com", "+919400200014", false);
        SeededStaff tgt = seedStaff(t, managerRole(t.id()), "p14y@a.com", "+919400200015", false);
        UUID billing = seedRole(t.id(), "Billing", false, g("billing", true, true, true, false, false));
        ResponseEntity<Map> r = delegate(token(mgr), tgt.id(), billing);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r)).endsWith("grant-exceeds-base-role");
    }

    // ---- AC3 chained: the *delegate* cannot pass on, even when role is within their own base ----
    @Test
    void ac3_delegateCannotRedelegateEvenWhenTheirOwnBaseWouldCoverIt() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = seedStaff(t, managerRole(t.id()), "p14c1@a.com", "+919400200021", false);
        SeededStaff mid = seedStaff(t, managerRole(t.id()), "p14c2@a.com", "+919400200022", false);
        SeededStaff last = seedStaff(t, managerRole(t.id()), "p14c3@a.com", "+919400200023", false);
        UUID cover = seedRole(t.id(), "CoverSub", false, g("clinical", true, false, false, false, false)); // within mid's base too
        assertThat(delegate(token(mgr), mid.id(), cover).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = delegate(token(mid), last.id(), cover);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r)).endsWith("chained-delegation");
    }

    // ---- AC4 Owner needs step-up ----
    @Test
    void ac4_ownerRoleNeedsAStepUpTokenOfTheRightPurposeAndSubject() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, ownerRole, "p14s@a.com", "+919400200031", true);
        SeededStaff other = seedStaff(t, managerRole(t.id()), "p14s2@a.com", "+919400200032", false);
        SeededStaff target = seedStaff(t, managerRole(t.id()), "p14s3@a.com", "+919400200033", false);
        String ot = mfaLogin(t, owner);
        Instant exp = Instant.now().plusSeconds(3600);
        for (String bad : new String[]{null, "", "garbage", ot, stepUpWithPurpose(ot, "break_glass", null), stepUpWithPurpose(ot, "mfa_pending", null),
                stepUpWithPurpose(ot, "step_up", other.id().toString())}) {
            ResponseEntity<Map> r = delegate(ot, target.id(), ownerRole, exp, bad);
            assertThat(r.getStatusCode()).as("stepUp=%s", bad).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(type(r)).endsWith("step-up-required");
        }
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM role_delegations", Integer.class))).isZero();
        assertThat(delegate(ot, target.id(), ownerRole, exp, realStepUp(ot)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // a non-Owner role needs no step-up
        UUID small = seedRole(t.id(), "Small", false, g("clinical", true, false, false, false, false));
        assertThat(delegate(ot, other.id(), small, exp, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- AC5 sessions revoked + audit + event, both directions ----
    @Test
    void ac5_createAndRevokeEndTargetSessionsAtOnce_writeAuditWithRoleIds_publishEvents_andRevokeIsIdempotent() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "p14r@a.com", "+919400200041", false);
        SeededStaff target = seedStaff(t, managerRole(t.id()), "p14r2@a.com", "+919400200042", false);
        SeededStaff bystander = seedStaff(t, managerRole(t.id()), "p14r3@a.com", "+919400200043", false);
        UUID cover = seedRole(t.id(), "CoverR", false, g("clinical", true, false, false, false, false));
        String ownerTok = token(owner);
        String targetOld = token(target);
        String bystanderTok = token(bystander);

        ResponseEntity<Map> c = delegate(ownerTok, target.id(), cover);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) c.getBody().get("id");
        assertThat(exchange("/v1/roles", HttpMethod.GET, authed(targetOld), Map.class).getStatusCode()).as("target session revoked on create").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/roles", HttpMethod.GET, authed(bystanderTok), String.class).getStatusCode()).as("bystander untouched").isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/roles", HttpMethod.GET, authed(ownerTok), String.class).getStatusCode()).as("grantor untouched").isEqualTo(HttpStatus.OK);

        String targetFresh = token(target);
        ResponseEntity<Void> rev = exchange("/v1/roles/delegations/" + id, HttpMethod.DELETE, authed(ownerTok), Void.class);
        assertThat(rev.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/roles", HttpMethod.GET, authed(targetFresh), Map.class).getStatusCode()).as("target session revoked on revoke").isEqualTo(HttpStatus.UNAUTHORIZED);

        String created = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'role.delegation_created' AND entity_id = ?", String.class, UUID.fromString(id)));
        assertThat(created).contains(cover.toString()).contains(target.id().toString());
        String revoked = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT before::text FROM audit_log WHERE action = 'role.delegation_revoked' AND entity_id = ?", String.class, UUID.fromString(id)));
        assertThat(revoked).contains(cover.toString());
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForList("SELECT payload->>'change' FROM outbox_events WHERE event_type = 'iam.delegation.changed.v1' AND payload->>'delegationId' = ? ORDER BY created_at",
                String.class, id))).containsExactlyInAnyOrder("created", "revoked");
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE staff_id = ? AND revoked_at IS NULL", Integer.class, target.id()))).isZero();

        // idempotent revoke (story: idempotent=true): a second DELETE must not be a server error
        ResponseEntity<Void> again = exchange("/v1/roles/delegations/" + id, HttpMethod.DELETE, authed(ownerTok), Void.class);
        assertThat(again.getStatusCode().value()).as("second revoke").isIn(204, 404);
    }

    // ---- boundary / refusal paths ----
    @Test
    void permissionsAndInputRefusals() {
        SeededTenant t = seedTenant();
        SeededTenant other = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "p14p@a.com", "+919400200051", false);
        SeededStaff viewer = seedStaff(t, seedRole(t.id(), "ViewOnly", false, g("staff", true, false, false, false, false)), "p14p2@a.com", "+919400200052", false);
        SeededStaff target = seedStaff(t, managerRole(t.id()), "p14p3@a.com", "+919400200053", false);
        SeededStaff foreign = seedStaff(other, managerRole(other.id()), "p14p4@a.com", "+919400200054", false);
        UUID cover = seedRole(t.id(), "CoverP", false, g("clinical", true, false, false, false, false));
        UUID foreignRole = seedRole(other.id(), "ForeignRole", false, g("clinical", true, false, false, false, false));
        String ot = token(owner);

        assertThat(delegate(token(viewer), target.id(), cover).getStatusCode()).as("no staff:edit").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/roles/delegations/" + UUID.randomUUID(), HttpMethod.DELETE, authed(token(viewer)), Void.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delegate(ot, target.id(), UUID.randomUUID()).getStatusCode()).as("unknown role").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delegate(ot, target.id(), foreignRole).getStatusCode()).as("other tenant's role").isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> toUnknown = delegate(ot, UUID.randomUUID(), cover);
        assertThat(toUnknown.getStatusCode().is5xxServerError()).as("unknown target staff must not be a 500: " + toUnknown.getBody()).isFalse();
        ResponseEntity<Map> toForeign = delegate(ot, foreign.id(), cover);
        assertThat(toForeign.getStatusCode().is5xxServerError()).as("other tenant's staff must not be a 500: " + toForeign.getBody()).isFalse();
        assertThat(toForeign.getStatusCode().is2xxSuccessful()).as("cross-tenant delegation must not succeed").isFalse();
        assertThat(delegate(ot, target.id(), cover, Instant.now().minusSeconds(60), null).getStatusCode()).as("past expiry").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delegate(ot, target.id(), cover, Instant.now().plusSeconds(31L * 86400), null).getStatusCode()).as("> 30 d").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delegate(ot, target.id(), cover, Instant.now().plusSeconds(30L * 86400 - 60), null).getStatusCode()).as("just under 30 d").isEqualTo(HttpStatus.CREATED);
        assertThat(exchange("/v1/roles/delegations", HttpMethod.POST, jsonBody(Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String writeBody(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
