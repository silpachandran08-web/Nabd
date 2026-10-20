package com.nabd.hms.platform;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S11 QA (independent) PLT-056: clamped, audited, value-stripped operator audit search; narrowed support grants. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaPlt056OperatorAuditTest extends ApiTestBase {

    @Autowired
    AuditService audit;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private SeededOperator op(String role) {
        return seedOperator("qa56-" + role + "-" + UUID.randomUUID() + "@nabd.test", role, false);
    }

    private String tok(SeededOperator o) {
        return platformLoginAndGetAccessToken(o);
    }

    private ResponseEntity<Map> get(String token, String path) {
        return http.exchange(java.net.URI.create(url(path)), HttpMethod.GET, authed(token), Map.class); // URI: no re-encoding of %20
    }

    private void seedRows(UUID tenant, int n) {
        for (int i = 0; i < n; i++) {
            audit.record(tenant, "system", null, "Nabd System", "System", null, "qa56.event", "patient", UUID.randomUUID(),
                    Map.of("dx", "secret-before-" + i), Map.of("dx", "secret-after-" + i));
        }
    }

    // ── AC1 ──

    @Test
    void pageSizeIsClampedDefault50Max100AndGarbageIs400Not500() {
        SeededTenant t = seedTenant();
        seedRows(t.id(), 120);
        String dpo = tok(op("compliance_dpo"));
        String base = "/v1/platform/audit-log?tenantId=" + t.id();

        ResponseEntity<Map> huge = get(dpo, base + "&limit=1000000");
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(huge.getBody().get("code")).isEqualTo("LIMIT_OUT_OF_RANGE");
        assertThat(get(dpo, base + "&limit=101").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(dpo, base + "&limit=abc").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> dflt = get(dpo, base);
        assertThat(dflt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List) dflt.getBody().get("data")).hasSize(50);
        ResponseEntity<Map> max = get(dpo, base + "&limit=100");
        assertThat((List) max.getBody().get("data")).hasSize(100);
    }

    // ── AC2 ──

    @Test
    void everySearchAndExportIsLoggedAndTheFilteredTenantAloneGetsATrailRow() {
        SeededTenant t = seedTenant();
        SeededTenant bystander = seedTenant();
        seedRows(t.id(), 7);
        SeededOperator dpo = op("compliance_dpo");
        String token = tok(dpo);

        ResponseEntity<Map> r = get(token, "/v1/platform/audit-log?limit=5&action=qa56.event&tenantId=" + t.id());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = su.queryForMap("SELECT operator_id, action, target_tenant_id, params::text AS params, row_count, created_at "
                + "FROM master.operator_audit_log WHERE operator_id = ? ORDER BY id DESC LIMIT 1", dpo.id());
        assertThat(row.get("action")).isEqualTo("audit_log.search");
        assertThat(row.get("target_tenant_id")).isEqualTo(t.id());
        assertThat(row.get("row_count")).isEqualTo(5);
        assertThat(row.get("params").toString()).contains("qa56.event");
        assertThat(row.get("created_at")).isNotNull();

        ResponseEntity<String> csv = exchange("/v1/platform/audit-log/export?tenantId=" + t.id(), HttpMethod.GET, authed(token), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> ex = su.queryForMap("SELECT action, row_count FROM master.operator_audit_log WHERE operator_id = ? ORDER BY id DESC LIMIT 1", dpo.id());
        assertThat(ex.get("action")).isEqualTo("audit_log.export");
        assertThat(((Number) ex.get("row_count")).intValue()).isGreaterThanOrEqualTo(7);

        Integer trail = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'operator.audit_search' AND actor_id = ?", Integer.class, t.id(), dpo.id()));
        assertThat(trail).as("the searched clinic sees the operator in its own audit trail").isGreaterThanOrEqualTo(1);
        Integer leak = inTenantTx(bystander.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'operator.audit_search'", Integer.class, bystander.id()));
        assertThat(leak).isZero();
        Long broken = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t.id()));
        assertThat(broken).as("hash chain intact after the operator row").isNull();
    }

    // ── AC3 ──

    @Test
    void sreAndSupportCannotUseTheConsoleAndAreNotLogged() {
        for (String role : List.of("sre", "support_engineer")) {
            SeededOperator o = op(role);
            String token = tok(o);
            assertThat(get(token, "/v1/platform/audit-log").getStatusCode()).as(role).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange("/v1/platform/audit-log/export", HttpMethod.GET, authed(token), String.class).getStatusCode()).as(role).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(su.queryForObject("SELECT count(*) FROM master.operator_audit_log WHERE operator_id = ?", Integer.class, o.id())).isZero();
        }
    }

    @Test
    void dpoSeesClinicalValuesOnlyWithANonBlankReason() {
        SeededTenant t = seedTenant();
        seedRows(t.id(), 1);
        SeededOperator dpo = op("compliance_dpo");
        String token = tok(dpo);
        String base = "/v1/platform/audit-log?tenantId=" + t.id();

        ResponseEntity<Map> plain = get(token, base);
        assertThat(plain.getBody().toString()).doesNotContain("secret-before-0").doesNotContain("secret-after-0");
        ResponseEntity<String> plainCsv = exchange("/v1/platform/audit-log/export?tenantId=" + t.id(), HttpMethod.GET, authed(token), String.class);
        assertThat(plainCsv.getBody()).doesNotContain("secret-after-0");

        for (String bad : List.of("&includeValues=true", "&includeValues=true&reason=", "&includeValues=true&reason=%20%20")) {
            ResponseEntity<Map> r = get(token, base + bad);
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code")).as(bad).isEqualTo("REASON_REQUIRED");
        }
        ResponseEntity<Map> shown = get(token, base + "&includeValues=true&reason=DSAR-77");
        assertThat(shown.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shown.getBody().toString()).contains("secret-after-0").contains("secret-before-0");
        assertThat(su.queryForObject("SELECT reason FROM master.operator_audit_log WHERE operator_id = ? ORDER BY id DESC LIMIT 1", String.class, dpo.id()))
                .isEqualTo("DSAR-77");

        // a non-DPO console user never gets values, even with a reason
        ResponseEntity<Map> admin = get(tok(op("super_admin")), base + "&includeValues=true&reason=curious");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── AC4 ──

    private UUID ticket(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO master.support_tickets (id, tenant_id, source, raised_by_name, raised_by_role, subject, description, sla_due_at) "
                + "VALUES (?, ?, 'system', 'Nabd System', 'System', 'QA56', 'd', now() + interval '1 day')", id, tenantId);
        return id;
    }

    private ResponseEntity<Map> grant(String token, UUID tenant, UUID ticket, String scope, Integer minutes) {
        Map<String, Object> b = new HashMap<>();
        b.put("tenantId", tenant.toString());
        if (ticket != null) b.put("ticketId", ticket.toString());
        if (scope != null) b.put("scope", scope);
        if (minutes != null) b.put("minutes", minutes);
        b.put("reason", "QA56 ticket follow-up");
        return exchange("/v1/platform/support-access/grants", HttpMethod.POST, authedJsonBody(token, b), Map.class);
    }

    @Test
    void supportGrantNeedsTicketNarrowScopeAndAtMostAnHour() {
        SeededTenant t = seedTenant();
        UUID tk = ticket(t.id());
        String token = tok(op("support_engineer"));
        assertCode(grant(token, t.id(), null, "config_only", 30), "TICKET_REQUIRED");
        assertCode(grant(token, t.id(), tk, "full_access", 30), "SCOPE_INVALID");
        assertCode(grant(token, t.id(), tk, "CONFIG_ONLY", 30), "SCOPE_INVALID");
        assertCode(grant(token, t.id(), tk, "config_only", 61), "DURATION_OUT_OF_RANGE");
        assertCode(grant(token, t.id(), ticket(seedTenant().id()), "config_only", 30), "TICKET_INVALID");
        ResponseEntity<Map> ok = grant(token, t.id(), tk, "config_only", 60);
        assertThat(ok.getStatusCode()).as("60 minutes is the boundary").isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("scope")).isEqualTo("config_only");
        assertThat(ok.getBody().get("ticketId")).isEqualTo(tk.toString());
    }

    private static void assertCode(ResponseEntity<Map> r, String code) {
        assertThat(r.getStatusCode()).as(code).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).as(code).isEqualTo(code);
    }

    @Test
    void anExpiredGrantNoLongerOpensAnything() {
        SeededTenant t = seedTenant();
        SeededOperator o = op("support_engineer");
        String token = tok(o);
        ResponseEntity<Map> g = grant(token, t.id(), ticket(t.id()), "redacted_patient_view", 10);
        assertThat(g.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object id = g.getBody().get("id");
        su.update("UPDATE master.support_access_grants SET granted_at = granted_at - interval '2 hours', expires_at = expires_at - interval '2 hours' WHERE id = ?::uuid", id);
        ResponseEntity<Map> view = get(token, "/v1/platform/support-access/grants/" + id + "/patients/" + UUID.randomUUID());
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(view.getBody().get("type"))).contains("grant-not-active");
    }

    // ── AC5 ──

    @Test
    void nabdAppCannotExecuteAnySearchAuditLogOverload() throws SQLException {
        List<String> sigs = su.queryForList("SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.proname = 'search_audit_log'", String.class);
        assertThat(sigs).isNotEmpty();
        for (String sig : sigs) {
            assertThat(su.queryForObject("SELECT has_function_privilege('nabd_app', ?, 'EXECUTE')", Boolean.class, sig)).as(sig).isFalse();
        }
        try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection()) {
            JdbcTemplate app = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true));
            assertThatThrownBy(() -> app.queryForList("SELECT * FROM search_audit_log(NULL::uuid, NULL, NULL, NULL::timestamptz, NULL::timestamptz, NULL::bigint, 1)"))
                    .hasStackTraceContaining("permission denied");
        }
    }
}
