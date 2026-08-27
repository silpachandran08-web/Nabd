package com.nabd.hms.staff;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffApiTest extends ApiTestBase {

    @Test
    void listReturnsSeededStaff() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "list@a.com", "+919100000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/staff", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> data = (List<?>) resp.getBody().get("data");
        assertThat(data).isNotEmpty();
    }

    /**
     * The bug this catches: arrivals/nursing doctor pickers used to call GET /v1/staff (gated on
     * staff:view, an HR-data permission), so a Reception/Nursing role that only holds queue:view
     * got a 403 and an empty dropdown. /roster is the narrow id+name fix for exactly that role.
     */
    @Test
    void rosterIsUsableByAQueueViewOnlyRoleAndOnlyExposesIdAndName() {
        SeededTenant tenant = seedTenant();
        UUID receptionRole = seedRole(tenant.id(), "Receptionist", false, fullGrant("queue"));
        SeededStaff reception = seedStaff(tenant, receptionRole, "reception@a.com", "+919100000020", false);
        String token = loginAndGetAccessToken(reception);

        ResponseEntity<List> resp = exchange("/v1/staff/roster", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> entry = (Map<String, Object>) resp.getBody().get(0);
        assertThat(entry.get("id")).isEqualTo(reception.id().toString());
        assertThat(entry.get("name")).isEqualTo("Test Staff");
        assertThat(entry.keySet()).containsExactlyInAnyOrder("id", "name"); // no email/mobile/status leaking through
    }

    @Test
    void rosterExcludesStaffThatHaveNotAcceptedTheirInviteYet() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner-roster@a.com", "+919100000021", false);
        String token = loginAndGetAccessToken(owner);
        exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", "not-active-yet@a.com", "name", "Not Active Yet", "mobilePhone", "+919100000022",
                "roleId", roleId.toString())), Map.class);

        ResponseEntity<List> resp = exchange("/v1/staff/roster", HttpMethod.GET, authed(token), List.class);
        List<String> names = resp.getBody().stream().map(e -> (String) ((Map<String, Object>) e).get("name")).toList();
        assertThat(names).doesNotContain("Not Active Yet");
    }

    @Test
    void listStillRequiresStaffViewNotJustQueueView() {
        SeededTenant tenant = seedTenant();
        UUID receptionRole = seedRole(tenant.id(), "Receptionist", false, fullGrant("queue"));
        SeededStaff reception = seedStaff(tenant, receptionRole, "reception2@a.com", "+919100000023", false);
        String token = loginAndGetAccessToken(reception);

        ResponseEntity<Map> resp = exchange("/v1/staff", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void inviteCreatesInvitedStaffWithToken() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner@a.com", "+919100000002", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", "newhire@a.com", "name", "New Hire", "mobilePhone", "+919100000003",
                "roleId", roleId.toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("inviteToken");
        Map<?, ?> staffBody = (Map<?, ?>) resp.getBody().get("staff");
        assertThat(staffBody.get("status")).isEqualTo("invited");
    }

    @Test
    void inviteWithDuplicateEmailConflicts() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner2@a.com", "+919100000004", false);
        seedStaff(tenant, roleId, "taken@a.com", "+919100000005", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", "taken@a.com", "name", "Dup", "mobilePhone", "+919100000006",
                "roleId", roleId.toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("type")).asString().contains("staff-email-conflict");
    }

    @Test
    void inviteWithDuplicateMobileConflicts() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner3@a.com", "+919100000007", false);
        seedStaff(tenant, roleId, "someone@a.com", "+919100000008", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", "fresh@a.com", "name", "Dup Mobile", "mobilePhone", "+919100000008",
                "roleId", roleId.toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("type")).asString().contains("staff-mobile-conflict");
    }

    @Test
    void getReturns404ForUnknownId() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner4@a.com", "+919100000009", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/staff/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void patchUpdatesRoleAndScope() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        UUID otherRole = seedRole(tenant.id(), "Receptionist", false, fullGrant("queue"));
        SeededStaff owner = seedStaff(tenant, ownerRole, "owner5@a.com", "+919100000010", false);
        SeededStaff target = seedStaff(tenant, ownerRole, "target@a.com", "+919100000011", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/staff/" + target.id(), HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "roleId", otherRole.toString(), "scope", "own_patients_only")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("roleId")).isEqualTo(otherRole.toString());
        assertThat(resp.getBody().get("scope")).isEqualTo("own_patients_only");
    }

    @Test
    void acceptInviteActivatesStaffAndLogsIn() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner6@a.com", "+919100000012", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Map> inviteResp = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", "invitee@a.com", "name", "Invitee", "mobilePhone", "+919100000013",
                "roleId", roleId.toString())), Map.class);
        String inviteToken = (String) inviteResp.getBody().get("inviteToken");

        ResponseEntity<Map> acceptResp = http.postForEntity(url("/v1/staff/invitations/" + inviteToken + "/accept"),
                jsonBody(Map.of("pin", "5678")), Map.class);

        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acceptResp.getBody()).containsKey("accessToken");
    }

    @Test
    void acceptInviteWithBogusTokenFails() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/staff/invitations/not-a-real-token/accept"),
                jsonBody(Map.of("pin", "1234")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void suspendWithoutStepUpTokenIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner7@a.com", "+919100000014", false);
        SeededStaff target = seedStaff(tenant, roleId, "target2@a.com", "+919100000015", false);
        String token = loginAndGetAccessToken(owner);

        ResponseEntity<Void> resp = exchange("/v1/staff/" + target.id() + "/suspend", HttpMethod.POST, authed(token), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void suspendWithValidStepUpTokenKillsAllSessions() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner8@a.com", "+919100000016", true); // mfa-enrolled to obtain step-up
        SeededStaff target = seedStaff(tenant, roleId, "target3@a.com", "+919100000017", false);
        String ownerToken = mfaLogin(tenant, owner);
        String targetToken = loginAndGetAccessToken(target);

        String stepUpToken = obtainStepUpToken(ownerToken);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(ownerToken);
        headers.set("X-Step-Up-Token", stepUpToken);
        ResponseEntity<Void> resp = http.exchange(url("/v1/staff/" + target.id() + "/suspend"), HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(headers), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // suspend revokes all of target's sessions -> their existing access token's session is gone,
        // provable by trying to list their own sessions and getting a 401 from the resource server
        // (the JWT itself is still cryptographically valid until expiry; session-level revocation
        // shows up on the next refresh instead, so assert directly against the sessions table)
        Integer activeSessions = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE staff_id = ? AND revoked_at IS NULL", Integer.class, target.id()));
        assertThat(activeSessions).isZero();
        assertThat(targetToken).isNotBlank(); // sanity: token was actually issued before suspension
    }

    // ---- helpers ----

    private String mfaLogin(SeededTenant tenant, SeededStaff staff) {
        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        String challengeId = (String) loginResp.getBody().get("challengeId");
        ResponseEntity<Map> mfaResp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", currentTotpCode())), Map.class);
        return (String) mfaResp.getBody().get("accessToken");
    }

    private String obtainStepUpToken(String accessToken) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.http.HttpEntity<String> req = new org.springframework.http.HttpEntity<>(
                "{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", headers);
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/mfa/verify"), req, Map.class);
        return (String) resp.getBody().get("stepUpToken");
    }
}
