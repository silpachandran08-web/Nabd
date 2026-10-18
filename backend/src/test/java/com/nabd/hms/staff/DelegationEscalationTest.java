package com.nabd.hms.staff;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-014: no self-delegation, no chaining, grantable set = base role only, Owner needs step-up, sessions revoked. */
class DelegationEscalationTest extends ApiTestBase {

    private ResponseEntity<Map> delegate(String token, UUID staffId, UUID roleId, Instant expiresAt, String stepUp) {
        Map<String, String> body = Map.of("staffId", staffId.toString(), "delegatedRoleId", roleId.toString(),
                "reason", "cover", "expiresAt", expiresAt.toString());
        HttpEntity<String> req = stepUp == null ? authedJsonBody(token, body)
                : authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp, body);
        return exchange("/v1/roles/delegations", HttpMethod.POST, req, Map.class);
    }

    private ResponseEntity<Map> delegate(String token, UUID staffId, UUID roleId) {
        return delegate(token, staffId, roleId, Instant.now().plusSeconds(3600), null);
    }

    private UUID managerRole(UUID tenantId) {
        return seedRole(tenantId, "Manager" + UUID.randomUUID().toString().substring(0, 6), false, fullGrant("staff"),
                new ModuleGrant("clinical", true, false, false, false, false, false, false));
    }

    @Test
    void selfDelegationIsRefused() {
        SeededTenant t = seedTenant();
        SeededStaff mgr = seedStaff(t, managerRole(t.id()), "m1@a.com", "+919400000001", false);
        UUID cover = seedRole(t.id(), "Cover", false, new ModuleGrant("clinical", true, false, false, false, false, false, false));

        ResponseEntity<Map> resp = delegate(loginAndGetAccessToken(mgr), mgr.id(), cover);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().endsWith("self-delegation");
    }

    @Test
    void breakGlassHolderCannotDelegateBeyondBaseRole() {
        SeededTenant t = seedTenant();
        seedFullAccessRole(t.id()); // break-glass elevates to built-in Owner
        UUID billing = seedRole(t.id(), "Billing", false, fullGrant("billing"));
        SeededStaff mgr = seedStaff(t, managerRole(t.id()), "m2@a.com", "+919400000002", false);
        SeededStaff other = seedStaff(t, managerRole(t.id()), "o2@a.com", "+919400000003", false);
        String token = loginAndGetAccessToken(mgr);
        String elevated = (String) exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "emergency")), Map.class).getBody().get("accessToken");

        ResponseEntity<Map> resp = delegate(elevated, other.id(), billing);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().endsWith("grant-exceeds-base-role");
    }

    @Test
    void delegateCannotReDelegateTheBorrowedRole() {
        SeededTenant t = seedTenant();
        UUID staffCover = seedRole(t.id(), "Staff Cover", false, fullGrant("staff"));
        SeededStaff mgr = seedStaff(t, managerRole(t.id()), "m3@a.com", "+919400000004", false);
        SeededStaff nurse = seedStaff(t, seedRole(t.id(), "Nurse", false,
                new ModuleGrant("clinical", true, false, false, false, false, false, false)), "n3@a.com", "+919400000005", false);
        SeededStaff third = seedStaff(t, managerRole(t.id()), "t3@a.com", "+919400000006", false);
        assertThat(delegate(loginAndGetAccessToken(mgr), nurse.id(), staffCover).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> resp = delegate(loginAndGetAccessToken(nurse), third.id(), staffCover); // nurse now has staff:edit

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().endsWith("chained-delegation");
    }

    @Test
    void ownerDelegationNeedsStepUpAndRevokesTargetSessionsWithAudit() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, ownerRole, "ow4@a.com", "+919400000007", true);
        SeededStaff target = seedStaff(t, managerRole(t.id()), "tg4@a.com", "+919400000008", false);
        String ownerToken = mfaLogin(t, owner, null);
        String targetToken = loginAndGetAccessToken(target);
        Instant exp = Instant.now().plusSeconds(3600);

        ResponseEntity<Map> noStepUp = delegate(ownerToken, target.id(), ownerRole, exp, null);
        assertThat(noStepUp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noStepUp.getBody().get("type")).asString().endsWith("step-up-required");

        ResponseEntity<Map> ok = delegate(ownerToken, target.id(), ownerRole, exp, stepUpToken(ownerToken));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String delegationId = (String) ok.getBody().get("id");

        // old access token dies at once (session revoked), a fresh login carries the delegated grants
        assertThat(exchange("/v1/roles", HttpMethod.GET, authed(targetToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auditCount(t, "role.delegation_created", delegationId)).isEqualTo(1);
        assertThat(outboxCount(t, delegationId)).isEqualTo(1);

        String fresh = loginAndGetAccessToken(target);
        assertThat(exchange("/v1/roles/delegations/" + delegationId, HttpMethod.DELETE, authed(ownerToken), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/roles", HttpMethod.GET, authed(fresh), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auditCount(t, "role.delegation_revoked", delegationId)).isEqualTo(1);
    }

    @Test
    void revokingUnknownDelegationIs404AndWindowOver30DaysIs400() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "ow5@a.com", "+919400000009", false);
        SeededStaff other = seedStaff(t, managerRole(t.id()), "ot5@a.com", "+919400000010", false);
        UUID cover = seedRole(t.id(), "Cover5", false, new ModuleGrant("clinical", true, false, false, false, false, false, false));
        String token = loginAndGetAccessToken(owner);

        assertThat(exchange("/v1/roles/delegations/" + UUID.randomUUID(), HttpMethod.DELETE, authed(token), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delegate(token, other.id(), cover, Instant.now().plusSeconds(31L * 86400), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(delegate(token, other.id(), cover, Instant.now().plusSeconds(29L * 86400), null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void delegationsAreTenantScoped() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        SeededStaff ownerA = seedStaff(a, seedFullAccessRole(a.id()), "oa6@a.com", "+919400000011", false);
        SeededStaff tgtA = seedStaff(a, managerRole(a.id()), "ta6@a.com", "+919400000012", false);
        SeededStaff ownerB = seedStaff(b, seedFullAccessRole(b.id()), "ob6@b.com", "+919400000013", false);
        UUID cover = seedRole(a.id(), "Cover6", false, new ModuleGrant("clinical", true, false, false, false, false, false, false));
        String id = (String) delegate(loginAndGetAccessToken(ownerA), tgtA.id(), cover).getBody().get("id");

        assertThat(exchange("/v1/roles/delegations/" + id, HttpMethod.DELETE, authed(loginAndGetAccessToken(ownerB)), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private Integer auditCount(SeededTenant t, String action, String entityId) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, UUID.fromString(entityId)));
    }

    private Integer outboxCount(SeededTenant t, String delegationId) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'iam.delegation.changed.v1' AND payload->>'delegationId' = ?",
                Integer.class, delegationId));
    }

    private String mfaLogin(SeededTenant t, SeededStaff s, Object unused) {
        String challengeId = (String) http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", t.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class).getBody().get("challengeId");
        return (String) http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", currentTotpCode())), Map.class).getBody().get("accessToken");
    }

    private String stepUpToken(String accessToken) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(accessToken);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<String> req = new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", h);
        return (String) http.postForEntity(url("/v1/auth/mfa/verify"), req, Map.class).getBody().get("stepUpToken");
    }
}
