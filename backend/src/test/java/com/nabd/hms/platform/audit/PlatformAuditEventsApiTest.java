package com.nabd.hms.platform.audit;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** LIVE-08: every platform-operator write lands in audit_log — tenant chain or the V126 platform chain. */
class PlatformAuditEventsApiTest extends ApiTestBase {

    @Test
    void signInSuccessAndFailureAreAuditedInThePlatformChainWithoutThePin() {
        SeededOperator op = seedOperator("aud-signin-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        ResponseEntity<Map> bad = http.postForEntity(url("/v1/platform/auth/login"),
                jsonBody(Map.of("email", op.email(), "pin", "987650")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        platformLoginAndGetAccessToken(op);

        Map<String, Object> failed = single(op.id(), "platform_auth.sign_in_failed");
        assertThat(failed.get("tenant_id")).isNull();
        assertThat(failed.get("actor_type")).isEqualTo("operator");
        assertThat(failed.get("after").toString()).contains("invalid_credentials").doesNotContain("987650");
        Map<String, Object> ok = single(op.id(), "platform_auth.signed_in");
        assertThat(ok.get("after").toString()).doesNotContain(STAFF_PIN);
        assertThat(ok.get("actor_role")).isEqualTo("super_admin");
    }

    @Test
    void unknownAccountSignInFailureIsAuditedWithoutTheTypedEmail() {
        String email = "nobody-" + UUID.randomUUID() + "@nabd.health";
        http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", email, "pin", "123456")), Map.class);
        List<Map<String, Object>> rows = su().queryForList("SELECT after::text AS after, actor_id FROM search_audit_log("
                + "NULL, 'platform_auth.sign_in_failed', NULL, now() - interval '1 minute', NULL, NULL, NULL, NULL, NULL, NULL, 1000)");
        assertThat(rows).anyMatch(r -> r.get("actor_id") == null && r.get("after").toString().contains("unknown_account"));
        assertThat(rows).noneMatch(r -> r.get("after").toString().contains(email));
    }

    @Test
    void logoutAndPlanWritesAreAuditedAndShowUpInTheSearchApiWithANullTenant() {
        SeededOperator op = seedOperator("aud-plan-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);
        String code = "aud-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = Map.of("code", code, "name", "Growth", "monthlyPriceCents", 500000,
                "currency", "INR", "seatLimit", 5, "active", true);
        ResponseEntity<Map> created = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String planId = (String) created.getBody().get("id");
        Map<String, Object> patch = new java.util.HashMap<>(body);
        patch.put("monthlyPriceCents", 600000);
        exchange("/v1/platform/plans/" + planId, HttpMethod.PATCH, authedJsonBody(token, patch), Map.class);

        assertThat(single(op.id(), "platform_plan.created").get("entity_id").toString()).isEqualTo(planId);
        Map<String, Object> updated = single(op.id(), "platform_plan.updated");
        assertThat(updated.get("before").toString()).contains("500000");
        assertThat(updated.get("after").toString()).contains("600000");

        exchange("/v1/platform/auth/logout", HttpMethod.POST, authed(token), Void.class);
        single(op.id(), "platform_auth.signed_out");

        // The cross-tenant search must not choke on tenant-less rows (was UUID.fromString(null)).
        String dpo = platformLoginAndGetAccessToken(
                seedOperator("aud-dpo-" + UUID.randomUUID() + "@nabd.health", "compliance_dpo", false));
        ResponseEntity<Map> search = exchange("/v1/platform/audit-log?limit=100&actorId=" + op.id(), HttpMethod.GET,
                authed(dpo), Map.class);
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) search.getBody().get("data");
        assertThat(rows).extracting(r -> r.get("action"))
                .contains("platform_auth.signed_in", "platform_plan.created", "platform_plan.updated", "platform_auth.signed_out");
        assertThat(rows).allMatch(r -> r.get("tenantId") == null && r.get("tenantName") == null);

        ResponseEntity<String> csv = exchange("/v1/platform/audit-log/export?actorId=" + op.id(), HttpMethod.GET,
                authed(dpo), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getBody()).contains("platform_plan.created").doesNotContain(",null,");

        assertPlatformChainIntact();
    }

    @Test
    void tenantLifecycleSubscriptionAndTicketWritesLandInThatTenantsChain() {
        SeededTenant tenant = seedTenant();
        SeededOperator op = seedOperator("aud-ten-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);

        // Ticket first: a suspended tenant's staff can no longer sign in to raise one.
        UUID roleId = seedRole(tenant.id(), "Nurse", false, fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "aud-ticket-" + UUID.randomUUID() + "@t.health", "+911234500001", false);
        ResponseEntity<Map> raised = http.exchange(url("/v1/support/tickets"), HttpMethod.POST,
                authedJsonBody(loginAndGetAccessToken(staff), Map.of("subject", "secret subject", "description", "d")), Map.class);
        String ticketId = (String) raised.getBody().get("id");
        http.exchange(url("/v1/platform/support/tickets/" + ticketId + "/transitions"), HttpMethod.POST,
                authedJsonBody(token, Map.of("toStatus", "in_progress")), Map.class);

        UUID planId = seedPlan("aud-sub-" + tenant.slug(), 500000, "INR", 5);
        assertThat(exchange("/v1/platform/billing/subscriptions/" + tenant.id(), HttpMethod.POST,
                authedJsonBody(token, Map.of("planId", planId.toString(), "mrrCents", 500000,
                        "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(token, Map.of("toStatus", "suspended", "reason", "non-payment")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT action, actor_id, after::text AS after FROM audit_log WHERE tenant_id = ? AND actor_type = 'operator' ORDER BY id",
                tenant.id()));
        assertThat(rows).extracting(r -> r.get("action")).containsExactly(
                "platform_ticket.status_changed", "platform_billing.subscription_created", "platform_tenant.lifecycle_changed");
        assertThat(rows).allMatch(r -> op.id().toString().equals(r.get("actor_id").toString()));
        assertThat(rows.get(0).get("after").toString()).doesNotContain("secret subject");
        assertThat(rows.get(2).get("after").toString()).contains("suspended").contains("non-payment");
        Long broken = inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, tenant.id()));
        assertThat(broken).isNull();
    }

    @Test
    void provisioningJobCreationIsAuditedWithoutOwnerContactDetails() {
        SeededOperator op = seedOperator("aud-prov-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);
        String slug = "aud" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> job = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Audit Clinic", "region", "IN", "ownerEmail", "owner-" + slug + "@x.health",
                "ownerName", "Owner Person", "ownerMobile", "+919876500001", "brandName", "B " + slug, "path", "self_serve")), Map.class);
        assertThat(job.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> row = single(op.id(), "platform_provisioning.job_created");
        assertThat(row.get("entity_id").toString()).isEqualTo(job.getBody().get("id"));
        assertThat(row.get("after").toString()).contains(slug).doesNotContain("owner-").doesNotContain("Owner Person")
                .doesNotContain("+919876500001");
    }

    private Map<String, Object> single(UUID operatorId, String action) {
        List<Map<String, Object>> rows = su().queryForList("SELECT tenant_id, actor_type, actor_role, entity_id, "
                + "before::text AS before, after::text AS after FROM search_audit_log("
                + "NULL, ?, NULL, NULL, NULL, ?::uuid, NULL, NULL, NULL, NULL, 10)", action, operatorId);
        assertThat(rows).as(action).hasSize(1);
        return rows.get(0);
    }

    /** PLT-056: nabd_app holds no EXECUTE on search_audit_log any more, so the test reads it as superuser. */
    private static org.springframework.jdbc.core.JdbcTemplate su() {
        return new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource());
    }

    /** As superuser: the app role cannot see platform rows directly (RLS), only through search_audit_log. */
    private void assertPlatformChainIntact() {
        try (Connection su = superuserConnection(); Statement st = su.createStatement();
             ResultSet rs = st.executeQuery("SELECT verify_audit_chain(NULL), count(*) FROM audit_log WHERE tenant_id IS NULL")) {
            rs.next();
            assertThat(rs.getObject(1)).isNull();
            assertThat(rs.getLong(2)).isPositive();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
