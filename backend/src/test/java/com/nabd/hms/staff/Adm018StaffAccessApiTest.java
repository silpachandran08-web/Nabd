package com.nabd.hms.staff;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-018: owner staff screen backend: invite response carries inviteId/expiresAt, resend after 72 h, MFA reminder, suspend ends the session. */
class Adm018StaffAccessApiTest extends ApiTestBase {

    private Map<?, ?> invite(String token, UUID role, String email, String phone) {
        ResponseEntity<Map> r = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(token, Map.of("email", email, "name", "New Hire", "mobilePhone", phone, "roleId", role.toString())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private ResponseEntity<Map> accept(String inviteToken) {
        return http.postForEntity(url("/v1/staff/invitations/" + inviteToken + "/accept"), jsonBody(Map.of("pin", "483926")), Map.class);
    }

    private ResponseEntity<Map> post(String path, String token) {
        return exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
    }

    @Test
    void inviteReturnsInviteIdAndSeventyTwoHourExpiry() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        String owner = loginAndGetAccessToken(seedStaff(t, role, "o1@a.com", "+919810000001", false));
        Map<?, ?> body = invite(owner, role, "n1@a.com", "+919810000002");
        assertThat(body.get("inviteId")).isEqualTo(((Map<?, ?>) body.get("staff")).get("id"));
        long hours = java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse((String) body.get("expiresAt"))).toHours();
        assertThat(hours).isBetween(70L, 72L);
    }

    @Test
    void expiredInviteIsResentInOneCallAndOnlyTheNewLinkWorks() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        String owner = loginAndGetAccessToken(seedStaff(t, role, "o2@a.com", "+919810000003", false));
        Map<?, ?> inv = invite(owner, role, "n2@a.com", "+919810000004");
        UUID id = UUID.fromString((String) inv.get("inviteId"));
        String oldToken = (String) inv.get("inviteToken");
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET invite_expires_at = now() - interval '1 hour' WHERE id = ?", id));
        assertThat(accept(oldToken).getStatusCode()).isEqualTo(HttpStatus.GONE); // after 72 h the link is expired

        ResponseEntity<Map> resent = post("/v1/staff/" + id + "/invite/resend", owner);
        assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newToken = (String) resent.getBody().get("inviteToken");
        assertThat(newToken).isNotBlank().isNotEqualTo(oldToken);
        assertThat(accept(oldToken).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(accept(newToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer audited = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'staff.invite_resent' AND entity_id = ?", Integer.class, id));
        assertThat(audited).isEqualTo(1);
        // already accepted: nothing to resend
        assertThat(post("/v1/staff/" + id + "/invite/resend", owner).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resendIsPermissionGatedTenantIsolatedAnd404ForUnknown() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        UUID roleA = seedFullAccessRole(a.id());
        String ownerA = loginAndGetAccessToken(seedStaff(a, roleA, "o3@a.com", "+919810000005", false));
        String ownerB = loginAndGetAccessToken(seedStaff(b, seedFullAccessRole(b.id()), "o3@b.com", "+919810000006", false));
        UUID id = UUID.fromString((String) invite(ownerA, roleA, "n3@a.com", "+919810000007").get("inviteId"));
        UUID viewOnly = seedRole(a.id(), "Reception", false, new ModuleGrant("queue", true, true, false, false, false, false, false));
        String reception = loginAndGetAccessToken(seedStaff(a, viewOnly, "r3@a.com", "+919810000008", false));

        assertThat(post("/v1/staff/" + id + "/invite/resend", reception).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/staff/" + id + "/invite/resend", ownerB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/v1/staff/" + UUID.randomUUID() + "/invite/resend", ownerA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mfaReminderOnlyForActiveStaffWhoseRoleNeedsMfaAndHaveNotEnrolled() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        String owner = loginAndGetAccessToken(seedStaff(t, ownerRole, "o4@a.com", "+919810000009", false));
        UUID mfaRole = seedRole(t.id(), "Pharmacist", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        inTenantTx(t.id(), () -> jdbc.update("UPDATE roles SET mfa_required = true WHERE id = ?", mfaRole));
        SeededStaff needs = seedStaff(t, mfaRole, "p4@a.com", "+919810000010", false);
        SeededStaff enrolled = seedStaff(t, mfaRole, "p5@a.com", "+919810000011", true);
        SeededStaff noPolicy = seedStaff(t, seedRole(t.id(), "Clerk", false, new ModuleGrant("queue", true, false, false, false, false, false, false)), "c4@a.com", "+919810000012", false);

        assertThat(post("/v1/staff/" + enrolled.id() + "/mfa/remind", owner).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/v1/staff/" + noPolicy.id() + "/mfa/remind", owner).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // no approved template yet: refused visibly, nothing is claimed as sent
        assertThat(post("/v1/staff/" + needs.id() + "/mfa/remind", owner).getStatusCode().value()).isEqualTo(422);

        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'mfa_enrol_reminder', ?, 'en', 'UTILITY', 'Clinic', 'Please finish setting up your Nabd authenticator.', 0, 'approved')
                """, t.id(), t.slug() + "_mfa_enrol_reminder"));
        assertThat(post("/v1/staff/" + needs.id() + "/mfa/remind", owner).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/v1/staff/" + needs.id() + "/mfa/remind", owner).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // same clinic day: deduped, not sent twice
        Integer audited = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'staff.mfa_reminder_sent' AND entity_id = ?", Integer.class, needs.id()));
        assertThat(audited).isEqualTo(2);
    }

    @Test
    void suspendedStaffMembersNextRequestIs401() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, role, "o6@a.com", "+919810000013", true);
        SeededStaff rec = seedStaff(t, role, "r6@a.com", "+919810000014", false);
        String recToken = loginAndGetAccessToken(rec);
        assertThat(exchange("/v1/queue", HttpMethod.GET, authed(recToken), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", t.slug(), "email", owner.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> mfa = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", login.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        String ownerToken = (String) mfa.getBody().get("accessToken");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String stepUp = (String) http.postForEntity(url("/v1/auth/mfa/verify"), new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", h), Map.class).getBody().get("stepUpToken");
        h.set("X-Step-Up-Token", stepUp);
        long start = System.nanoTime();
        assertThat(http.exchange(url("/v1/staff/" + rec.id() + "/suspend"), HttpMethod.POST, new HttpEntity<>("{\"reason\":\"left the clinic\"}", h), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(exchange("/v1/queue", HttpMethod.GET, authed(recToken), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(60);
    }
}
