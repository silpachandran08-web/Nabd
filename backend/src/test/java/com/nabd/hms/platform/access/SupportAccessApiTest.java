package com.nabd.hms.platform.access;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SupportAccessApiTest extends SupportAccessTestSupport {

    @Test
    void operatorRequestsGrantAndItAppearsInTheList() {
        SeededTenant tenant = seedTenant();
        SeededOperator op = seedOperator("access-grant@nabd.health", "support_engineer", false);
        String token = platformLoginAndGetAccessToken(op);

        ResponseEntity<Map> resp = http.exchange(url("/v1/platform/support-access/grants"), HttpMethod.POST,
                authedJsonBody(token, grantBody(tenant, "Investigating stuck queue")),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("operatorRole")).isEqualTo("support_engineer");
        assertThat(body.get("status")).isEqualTo("requested"); // PLT-057: a request opens nothing until the owner approves
        assertThat(body.get("active")).isEqualTo(false);
        assertThat(body.get("revokedAt")).isNull();

        ResponseEntity<Map[]> list = exchange("/v1/platform/support-access/grants", HttpMethod.GET, authed(token), Map[].class);
        assertThat(List.of(list.getBody())).anyMatch(g -> body.get("id").equals(g.get("id")));
    }

    @Test
    void rolesWithoutSupportAccessViewAreForbidden() {
        SeededOperator billing = seedOperator("access-billing@nabd.health", "billing", false);
        String token = platformLoginAndGetAccessToken(billing);
        ResponseEntity<Map> resp = exchange("/v1/platform/support-access/grants", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void grantLetsItsOwnerViewARedactedPatientAndLogsAudit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "access-doc@ticket.health", "+919000000001", false);
        String staffToken = loginAndGetAccessToken(staff);
        ResponseEntity<Map> registered = http.exchange(url("/v1/patients"), HttpMethod.POST, authedJsonBody(staffToken,
                Map.of("name", "Access Patient", "phone", "+919000000099", "dob", "1990-01-01", "gender", "male")), Map.class);
        UUID patientId = UUID.fromString((String) registered.getBody().get("id"));

        SeededOperator op = seedOperator("access-view@nabd.health", "support_engineer", false);
        String opToken = platformLoginAndGetAccessToken(op);
        ResponseEntity<Map> grant = http.exchange(url("/v1/platform/support-access/grants"), HttpMethod.POST,
                authedJsonBody(opToken, grantBody(tenant, "Support ticket follow-up")), Map.class);
        String grantId = (String) grant.getBody().get("id");
        assertThat(decide(seedOwner(tenant).token(), grantId, "approve", null).getStatusCode()).isEqualTo(HttpStatus.OK); // PLT-057

        ResponseEntity<Map> view = exchange("/v1/platform/support-access/grants/" + grantId + "/patients/" + patientId,
                HttpMethod.GET, authed(opToken), Map.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        // PLT-057: initials, masked phone, birth year, MRN. Name, phone, dob, gender are no longer returned
        assertThat(view.getBody().keySet()).containsExactlyInAnyOrder("initials", "maskedPhone", "birthYear", "mrn");
        assertThat(view.getBody()).doesNotContainKeys("allergies", "chronicConditions", "activePackages", "outstandingBalance");

        List<Map<String, Object>> auditRows = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT action, entity_type, entity_id FROM audit_log WHERE tenant_id = ? AND action = 'patient.support_view'",
                tenant.id()));
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).get("entity_id")).isEqualTo(patientId);
    }

    @Test
    void aDifferentOperatorCannotUseSomeoneElsesGrant() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "access-doc2@ticket.health", "+919000000002", false);
        String staffToken = loginAndGetAccessToken(staff);
        ResponseEntity<Map> registered = http.exchange(url("/v1/patients"), HttpMethod.POST, authedJsonBody(staffToken,
                Map.of("name", "Other Patient", "phone", "+919000000098", "dob", "1991-01-01", "gender", "female")), Map.class);
        String patientId = (String) registered.getBody().get("id");

        SeededOperator opA = seedOperator("access-a@nabd.health", "support_engineer", false);
        String tokenA = platformLoginAndGetAccessToken(opA);
        ResponseEntity<Map> grant = http.exchange(url("/v1/platform/support-access/grants"), HttpMethod.POST,
                authedJsonBody(tokenA, grantBody(tenant, "A's investigation")), Map.class);
        String grantId = (String) grant.getBody().get("id");
        assertThat(decide(seedOwner(tenant).token(), grantId, "approve", null).getStatusCode()).isEqualTo(HttpStatus.OK); // PLT-057: approved, still only A's

        SeededOperator opB = seedOperator("access-b@nabd.health", "support_engineer", false);
        String tokenB = platformLoginAndGetAccessToken(opB);
        ResponseEntity<Map> viewAsB = exchange("/v1/platform/support-access/grants/" + grantId + "/patients/" + patientId,
                HttpMethod.GET, authed(tokenB), Map.class);
        assertThat(viewAsB.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewAsB.getBody().get("type")).asString().contains("not-your-grant");
    }

    @Test
    void revokedGrantCanNoLongerBeUsedAndCannotBeRevokedTwice() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "access-doc3@ticket.health", "+919000000003", false);
        String staffToken = loginAndGetAccessToken(staff);
        ResponseEntity<Map> registered = http.exchange(url("/v1/patients"), HttpMethod.POST, authedJsonBody(staffToken,
                Map.of("name", "Revoke Patient", "phone", "+919000000097", "dob", "1992-01-01", "gender", "male")), Map.class);
        String patientId = (String) registered.getBody().get("id");

        SeededOperator op = seedOperator("access-revoke@nabd.health", "support_engineer", false);
        String token = platformLoginAndGetAccessToken(op);
        ResponseEntity<Map> grant = http.exchange(url("/v1/platform/support-access/grants"), HttpMethod.POST,
                authedJsonBody(token, grantBody(tenant, "Will be revoked")), Map.class);
        String grantId = (String) grant.getBody().get("id");
        assertThat(decide(seedOwner(tenant).token(), grantId, "approve", null).getStatusCode()).isEqualTo(HttpStatus.OK); // PLT-057

        ResponseEntity<Void> revokeResp = http.exchange(url("/v1/platform/support-access/grants/" + grantId + "/revoke"),
                HttpMethod.POST, authed(token), Void.class);
        assertThat(revokeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> viewAfterRevoke = exchange("/v1/platform/support-access/grants/" + grantId + "/patients/" + patientId,
                HttpMethod.GET, authed(token), Map.class);
        assertThat(viewAfterRevoke.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewAfterRevoke.getBody().get("type")).asString().contains("grant-not-active");

        ResponseEntity<Map> revokeAgain = http.exchange(url("/v1/platform/support-access/grants/" + grantId + "/revoke"),
                HttpMethod.POST, authed(token), Map.class);
        assertThat(revokeAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void expiredGrantCanNoLongerBeUsed() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "access-doc4@ticket.health", "+919000000004", false);
        String staffToken = loginAndGetAccessToken(staff);
        ResponseEntity<Map> registered = http.exchange(url("/v1/patients"), HttpMethod.POST, authedJsonBody(staffToken,
                Map.of("name", "Expiry Patient", "phone", "+919000000096", "dob", "1993-01-01", "gender", "female")), Map.class);
        String patientId = (String) registered.getBody().get("id");

        SeededOperator op = seedOperator("access-expiry@nabd.health", "support_engineer", false);
        String token = platformLoginAndGetAccessToken(op);
        ResponseEntity<Map> grant = http.exchange(url("/v1/platform/support-access/grants"), HttpMethod.POST,
                authedJsonBody(token, grantBody(tenant, "Will expire")), Map.class);
        String grantId = (String) grant.getBody().get("id");
        assertThat(decide(seedOwner(tenant).token(), grantId, "approve", null).getStatusCode()).isEqualTo(HttpStatus.OK); // PLT-057

        // master.support_access_grants carries no RLS — a plain jdbc.update backdates it directly.
        jdbc.update("UPDATE master.support_access_grants SET expires_at = ?, window_ends_at = ?, window_started_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))),
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(11))), UUID.fromString(grantId));

        ResponseEntity<Map> view = exchange("/v1/platform/support-access/grants/" + grantId + "/patients/" + patientId,
                HttpMethod.GET, authed(token), Map.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(view.getBody().get("type")).asString().contains("grant-not-active");
    }

    /** PLT-056: a grant names a ticket of that tenant and a scope. */
    Map<String, Object> grantBody(SeededTenant tenant, String reason) {
        return Map.of("tenantId", tenant.id().toString(), "ticketId", seedTicket(tenant.id()).toString(),
                "scope", "redacted_patient_view", "reason", reason);
    }

    // master.support_tickets carries no RLS (operator plane), so the app connection can seed it directly.
    UUID seedTicket(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO master.support_tickets (id, tenant_id, source, raised_by_name, raised_by_role, subject, "
                + "description, sla_due_at) VALUES (?, ?, 'system', 'Nabd System', 'System', 'Grant test', 'd', now() + interval '1 day')",
                id, tenantId);
        return id;
    }
}
