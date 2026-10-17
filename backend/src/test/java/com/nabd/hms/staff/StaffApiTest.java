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
     * got a 403 and an empty dropdown. /roster is the narrow id+name fix for exactly that role —
     * and it's filtered to staff whose role is named "Doctor" (the only signal this schema has;
     * roles are free-text tenant-defined objects, not a fixed type), so the receptionist calling
     * it never sees themselves or any other non-doctor staff in the result.
     */
    @Test
    void rosterIsUsableByAQueueViewOnlyRoleAndOnlyReturnsDoctorsWithIdAndName() {
        SeededTenant tenant = seedTenant();
        UUID receptionRole = seedRole(tenant.id(), "Receptionist", false, fullGrant("queue"));
        UUID doctorRole = seedRole(tenant.id(), "Doctor", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff reception = seedStaff(tenant, receptionRole, "reception@a.com", "+919100000020", false);
        SeededStaff doctor = seedStaff(tenant, doctorRole, "doctor@a.com", "+919100000030", false);
        String token = loginAndGetAccessToken(reception);

        ResponseEntity<List> resp = exchange("/v1/staff/roster", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1); // reception themselves must not appear
        Map<String, Object> entry = (Map<String, Object>) resp.getBody().get(0);
        assertThat(entry.get("id")).isEqualTo(doctor.id().toString());
        assertThat(entry.get("name")).isEqualTo("Test Staff");
        assertThat(entry.keySet()).containsExactlyInAnyOrder("id", "name", "departmentName"); // no email/mobile/status leaking through
        assertThat(entry.get("departmentName")).isNull(); // seeded doctor has no department
    }

    /** REC-023: the arrivals doctor rail shows each doctor's department under the name. */
    @Test
    void rosterCarriesEachDoctorsDepartmentName() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRole, "owner-dept@a.com", "+919100000025", false);
        String token = loginAndGetAccessToken(owner);
        UUID doctorRole = seedRole(tenant.id(), "Doctor", false, fullGrant("queue"));
        SeededStaff doctor = seedStaff(tenant, doctorRole, "doctor-dept@a.com", "+919100000026", false);
        String dentalId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dental", "active", true)), Map.class).getBody().get("id");
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ?::uuid WHERE id = ?", dentalId, doctor.id()));

        ResponseEntity<List> resp = exchange("/v1/staff/roster", HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) resp.getBody()).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("id", doctor.id().toString()).containsEntry("departmentName", "Dental"));
    }

    @Test
    void rosterExcludesStaffThatHaveNotAcceptedTheirInviteYet() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRole, "owner-roster@a.com", "+919100000021", false);
        String token = loginAndGetAccessToken(owner);
        UUID doctorRole = seedRole(tenant.id(), "Doctor", false, fullGrant("queue"), fullGrant("patients"));
        seedStaff(tenant, doctorRole, "doctor-active@a.com", "+919100000024", false);
        exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", "not-active-yet@a.com", "name", "Not Active Yet", "mobilePhone", "+919100000022",
                "roleId", doctorRole.toString())), Map.class);

        ResponseEntity<List> resp = exchange("/v1/staff/roster", HttpMethod.GET, authed(token), List.class);
        List<String> names = resp.getBody().stream().map(e -> (String) ((Map<String, Object>) e).get("name")).toList();
        assertThat(names).contains("Test Staff").doesNotContain("Not Active Yet");
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

    // DOC-N1 (written, not executed here: needs Docker/Testcontainers).
    @Test
    void patchSetsChangesAndClearsProfessionalRegistrationNo() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRole, "owner-reg@a.com", "+919100000040", false);
        SeededStaff target = seedStaff(tenant, ownerRole, "target-reg@a.com", "+919100000041", false);
        String token = loginAndGetAccessToken(owner);
        String path = "/v1/staff/" + target.id();

        ResponseEntity<Map> set = exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("professionalRegistrationNo", "KMC 78412")), Map.class);
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("professionalRegistrationNo")).isEqualTo("KMC 78412");

        // a PATCH that does not mention it leaves it alone
        ResponseEntity<Map> other = exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("scope", "own_patients_only")), Map.class);
        assertThat(other.getBody().get("professionalRegistrationNo")).isEqualTo("KMC 78412");

        // markup / control characters are refused
        ResponseEntity<Map> bad = exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("professionalRegistrationNo", "<b>1</b>")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // empty string clears it
        ResponseEntity<Map> cleared = exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("professionalRegistrationNo", "")), Map.class);
        assertThat(cleared.getBody().get("professionalRegistrationNo")).isNull();
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

    private static Map<String, Object> byId(List<Map<String, Object>> data, UUID id) {
        return data.stream().filter(s -> id.toString().equals(s.get("id"))).findFirst().orElseThrow();
    }

    /** OWN-034: loginMethod/loginFactors are derived from stored credentials only; no hash ever leaves the API. */
    @Test
    @SuppressWarnings("unchecked")
    void listReportsEachStaffMembersLoginMethodFromStoredCredentials() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRole, "lm-owner@a.com", "+919100000091", false);
        SeededStaff withTotp = seedStaff(tenant, ownerRole, "lm-totp@a.com", "+919100000092", true);
        UUID mfaRole = seedRole(tenant.id(), "MfaRole", false, fullGrant("queue"));
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE roles SET mfa_required = true WHERE id = ?", mfaRole));
        SeededStaff mustEnrol = seedStaff(tenant, mfaRole, "lm-enrol@a.com", "+919100000093", false);
        SeededStaff otpOnly = seedStaff(tenant, ownerRole, "lm-otp@a.com", "+919100000094", false);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET pin_hash = NULL WHERE id = ?", otpOnly.id()));
        SeededStaff pinOnly = seedStaff(tenant, ownerRole, "lm-pin@a.com", "+919100000095", false);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET mobile_phone = NULL WHERE id = ?", pinOnly.id()));
        SeededOwner ownerAccount = seedOwner("lm-owner-account@a.com");
        SeededStaff ownerShadow = seedStaff(tenant, ownerRole, "lm-shadow@a.com", "+919100000096", false);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET owner_id = ? WHERE id = ?", ownerAccount.id(), ownerShadow.id()));
        String token = loginAndGetAccessToken(owner);
        exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of(
                "email", "lm-invited@a.com", "name", "Lm Invited", "mobilePhone", "+919100000097",
                "roleId", ownerRole.toString())), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/staff?limit=100", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.getBody().get("data");

        assertThat(byId(data, owner.id())).containsEntry("loginMethod", "pin_or_whatsapp_otp")
                .containsEntry("loginFactors", List.of("pin", "whatsapp_otp"));
        assertThat(byId(data, withTotp.id())).containsEntry("loginMethod", "authenticator")
                .containsEntry("loginFactors", List.of("pin", "whatsapp_otp", "authenticator"));
        assertThat(byId(data, mustEnrol.id())).containsEntry("loginMethod", "authenticator_setup_required");
        assertThat(byId(data, otpOnly.id())).containsEntry("loginMethod", "whatsapp_otp")
                .containsEntry("loginFactors", List.of("whatsapp_otp"));
        assertThat(byId(data, pinOnly.id())).containsEntry("loginMethod", "pin")
                .containsEntry("loginFactors", List.of("pin"));
        assertThat(byId(data, ownerShadow.id())).containsEntry("loginMethod", "owner_account")
                .containsEntry("loginFactors", List.of());
        Map<String, Object> invited = data.stream().filter(s -> "lm-invited@a.com".equals(s.get("email"))).findFirst().orElseThrow();
        assertThat(invited).containsEntry("loginMethod", "invite_pending").containsEntry("loginFactors", List.of());
        assertThat(data).allSatisfy(s -> assertThat(s.keySet()).doesNotContain("pinHash", "mfaSecretEnc", "hasPin"));

        // single-record GET carries the same derived field
        ResponseEntity<Map> one = exchange("/v1/staff/" + withTotp.id(), HttpMethod.GET, authed(token), Map.class);
        assertThat((Map<String, Object>) one.getBody()).containsEntry("loginMethod", "authenticator");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginMethodListIsTenantIsolated() { // OWN-034
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        SeededStaff staffA = seedStaff(tenantA, seedFullAccessRole(tenantA.id()), "lm-a@a.com", "+919100000098", true);
        SeededStaff staffB = seedStaff(tenantB, seedFullAccessRole(tenantB.id()), "lm-b@b.com", "+919100000099", false);
        String tokenB = loginAndGetAccessToken(staffB);

        List<Map<String, Object>> data = (List<Map<String, Object>>) exchange("/v1/staff", HttpMethod.GET, authed(tokenB), Map.class)
                .getBody().get("data");
        assertThat(data).extracting(s -> s.get("id")).containsExactly(staffB.id().toString());
        assertThat(exchange("/v1/staff/" + staffA.id(), HttpMethod.GET, authed(tokenB), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
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
