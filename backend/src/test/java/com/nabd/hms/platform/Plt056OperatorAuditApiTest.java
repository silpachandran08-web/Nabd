package com.nabd.hms.platform;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-056: operator audit searches are capped, audited and narrowed; support grants are scoped. */
class Plt056OperatorAuditApiTest extends ApiTestBase {

    private static final String SEARCH_11 =
            "search_audit_log(uuid,text,text,timestamptz,timestamptz,uuid,text,text,text,bigint,int)";
    private static final String SEARCH_7 = "search_audit_log(uuid,text,text,timestamptz,timestamptz,bigint,int)";

    @Autowired
    AuditService auditService;

    // ---- AC1: clamp -------------------------------------------------------------------------------------------

    @Test
    void limitIsClampedDefault50Max100() {
        String dpo = token("compliance_dpo");
        for (String bad : List.of("1000000", "101", "0", "-1")) {
            ResponseEntity<Map> resp = exchange("/v1/platform/audit-log?limit=" + bad, HttpMethod.GET, authed(dpo), Map.class);
            assertThat(resp.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().get("code")).as(bad).isEqualTo("LIMIT_OUT_OF_RANGE");
        }
        assertThat(page(dpo, "/v1/platform/audit-log").get("limit")).isEqualTo(50);
        assertThat(page(dpo, "/v1/platform/audit-log?limit=100").get("limit")).isEqualTo(100);
        assertThat(page(dpo, "/v1/platform/audit-log?limit=1").get("limit")).isEqualTo(1);
    }

    // ---- AC2: operator audit log + tenant row ------------------------------------------------------------------

    @Test
    void searchWithTenantFilterWritesOperatorAuditRowAndTenantAuditRow() {
        SeededTenant tenant = seedTenant();
        for (int i = 0; i < 3; i++) record(tenant.id(), "patient.update", Map.of("i", i));
        SeededOperator dpo = operator("compliance_dpo");
        String token = platformLoginAndGetAccessToken(dpo);

        ResponseEntity<Map> resp = exchange("/v1/platform/audit-log?limit=2&actorName=Zyxw&tenantId=" + tenant.id(),
                HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = lastOperatorRow(dpo.id());
        assertThat(row.get("action")).isEqualTo("audit_log.search");
        assertThat(row.get("target_tenant_id")).isEqualTo(tenant.id());
        assertThat(row.get("row_count")).isEqualTo(0); // actorName filter matches none of the system rows
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("params").toString()).contains(tenant.id().toString()).contains("actorNameFilter")
                .doesNotContainIgnoringCase("zyxw"); // no free-text search value (could be a name) in params

        exchange("/v1/platform/audit-log?limit=2&tenantId=" + tenant.id(), HttpMethod.GET, authed(token), Map.class);
        assertThat(lastOperatorRow(dpo.id()).get("row_count")).isEqualTo(2);

        List<Map<String, Object>> tenantRows = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT actor_id, actor_type, after::text AS after FROM audit_log WHERE tenant_id = ? "
                        + "AND action = 'operator.audit_search' ORDER BY id", tenant.id()));
        assertThat(tenantRows).hasSize(2);
        assertThat(tenantRows.get(0).get("actor_id")).isEqualTo(dpo.id());
        assertThat(tenantRows.get(0).get("actor_type")).isEqualTo("operator");
        assertThat(tenantRows.get(1).get("after").toString()).contains("\"rowCount\": 2");
        Long broken = inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, tenant.id()));
        assertThat(broken).isNull();
    }

    @Test
    void searchWithoutTenantFilterAndExportAreAuditedToo() {
        SeededTenant tenant = seedTenant();
        record(tenant.id(), "invoice.create", Map.of("amount", 1));
        record(tenant.id(), "invoice.create", Map.of("amount", 2));
        SeededOperator admin = operator("super_admin");
        String token = platformLoginAndGetAccessToken(admin);

        exchange("/v1/platform/audit-log?limit=5&action=invoice.create", HttpMethod.GET, authed(token), Map.class);
        Map<String, Object> search = lastOperatorRow(admin.id());
        assertThat(search.get("action")).isEqualTo("audit_log.search");
        assertThat(search.get("target_tenant_id")).isNull();
        assertThat(search.get("params").toString()).contains("invoice.create");

        ResponseEntity<String> csv = exchange("/v1/platform/audit-log/export?tenantId=" + tenant.id(),
                HttpMethod.GET, authed(token), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> export = lastOperatorRow(admin.id());
        assertThat(export.get("action")).isEqualTo("audit_log.export");
        assertThat(export.get("target_tenant_id")).isEqualTo(tenant.id());
        assertThat(export.get("row_count")).isEqualTo(2);
        Integer tenantSearchRows = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'operator.audit_search'", Integer.class, tenant.id()));
        assertThat(tenantSearchRows).isEqualTo(1);
    }

    @Test
    void operatorAuditLogIsAppendOnly() {
        SeededOperator dpo = operator("compliance_dpo");
        exchange("/v1/platform/audit-log?limit=1", HttpMethod.GET, authed(platformLoginAndGetAccessToken(dpo)), Map.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE master.operator_audit_log SET row_count = 0 WHERE operator_id = ?", dpo.id()))
                .hasStackTraceContaining("permission denied");
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        assertThatThrownBy(() -> su.update("DELETE FROM master.operator_audit_log WHERE operator_id = ?", dpo.id()))
                .hasStackTraceContaining("append-only");
    }

    // ---- AC3: sre 403, values only for compliance_dpo with a reason ---------------------------------------------

    @Test
    void sreIsForbiddenFromSearchAndExportAndNothingIsLogged() {
        SeededOperator sre = operator("sre");
        String token = platformLoginAndGetAccessToken(sre);
        assertThat(exchange("/v1/platform/audit-log", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/audit-log/export", HttpMethod.GET, authed(token), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.operator_audit_log WHERE operator_id = ?",
                Integer.class, sre.id())).isZero();
    }

    @Test
    void beforeAfterOnlyForComplianceDpoAfterAReason() {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.update", "patient", UUID.randomUUID(), Map.of("v", "old"), Map.of("v", "new"));
        SeededOperator dpo = operator("compliance_dpo");
        String dpoToken = platformLoginAndGetAccessToken(dpo);
        String base = "/v1/platform/audit-log?tenantId=" + tenant.id();

        Map<String, Object> hidden = first(dpoToken, base);
        assertThat(hidden.get("before")).isNull();
        assertThat(hidden.get("after")).isNull();
        assertThat(hidden.get("action")).isEqualTo("patient.update");

        for (String noReason : List.of("&includeValues=true", "&includeValues=true&reason=")) {
            ResponseEntity<Map> resp = exchange(base + noReason, HttpMethod.GET, authed(dpoToken), Map.class);
            assertThat(resp.getStatusCode()).as(noReason).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().get("code")).isEqualTo("REASON_REQUIRED");
        }

        Map<String, Object> shown = first(dpoToken, base + "&includeValues=true&reason=DSAR-1234");
        assertThat(((Map<String, Object>) shown.get("after")).get("v")).isEqualTo("new");
        assertThat(((Map<String, Object>) shown.get("before")).get("v")).isEqualTo("old");
        Map<String, Object> logged = lastOperatorRow(dpo.id());
        assertThat(logged.get("reason")).isEqualTo("DSAR-1234");
        assertThat(logged.get("params").toString()).contains("\"includeValues\": true");

        // super_admin keeps the console but never the values
        String admin = token("super_admin");
        ResponseEntity<Map> denied = exchange(base + "&includeValues=true&reason=x", HttpMethod.GET, authed(admin), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(first(admin, base).get("after")).isNull();

        // export: before/after columns empty unless the DPO asks with a reason
        String plain = exchange("/v1/platform/audit-log/export?tenantId=" + tenant.id(), HttpMethod.GET,
                authed(admin), String.class).getBody();
        assertThat(plain).doesNotContain("\"\"v\"\"").endsWith(",,\n");
        String withValues = exchange("/v1/platform/audit-log/export?includeValues=true&reason=DSAR-1234&tenantId=" + tenant.id(),
                HttpMethod.GET, authed(dpoToken), String.class).getBody();
        assertThat(withValues).contains("\"\"v\"\": \"\"new\"\"");
    }

    // ---- AC4: support grant narrowing -----------------------------------------------------------------------

    @Test
    void grantRequiresTicketValidScopeAndAtMost60Minutes() {
        SeededTenant tenant = seedTenant();
        SeededTenant other = seedTenant();
        UUID ticket = seedTicket(tenant.id());
        String token = token("support_engineer");

        assertGrantRefused(token, body(tenant.id(), null, "config_only", null), "TICKET_REQUIRED");
        assertGrantRefused(token, body(tenant.id(), ticket, "full_access", null), "SCOPE_INVALID");
        assertGrantRefused(token, body(tenant.id(), ticket, null, null), "SCOPE_INVALID");
        assertGrantRefused(token, body(tenant.id(), ticket, "config_only", 61), "DURATION_OUT_OF_RANGE");
        assertGrantRefused(token, body(tenant.id(), ticket, "config_only", 0), "DURATION_OUT_OF_RANGE");
        assertGrantRefused(token, body(tenant.id(), seedTicket(other.id()), "config_only", null), "TICKET_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.support_access_grants WHERE tenant_id = ?",
                Integer.class, tenant.id())).isZero();

        Map<String, Object> full = grant(token, body(tenant.id(), ticket, "redacted_patient_view", 60));
        assertThat(full.get("scope")).isEqualTo("redacted_patient_view");
        assertThat(full.get("ticketId")).isEqualTo(ticket.toString());
        assertThat(windowMinutes(full)).isEqualTo(60);
        // PLT-057: one open request per operator and ticket, so each further request names another ticket
        Map<String, Object> defaulted = grant(token, body(tenant.id(), seedTicket(tenant.id()), "config_only", null));
        assertThat(windowMinutes(defaulted)).isEqualTo(60);
        Map<String, Object> shortOne = grant(token, body(tenant.id(), seedTicket(tenant.id()), "config_only", 15));
        assertThat(windowMinutes(shortOne)).isEqualTo(15);

        // the DB enforces the ceiling and the scope set too
        // PLT-057: the ceiling now guards the approved window (the 24 h request deadline sits on expires_at)
        assertThatThrownBy(() -> jdbc.update("UPDATE master.support_access_grants SET window_started_at = now(), window_ends_at = now() + interval '61 minutes' WHERE id = ?::uuid",
                full.get("id"))).hasStackTraceContaining("support_access_grants_window_max_60_min");
        assertThatThrownBy(() -> jdbc.update("UPDATE master.support_access_grants SET scope = 'all' WHERE id = ?::uuid",
                full.get("id"))).hasStackTraceContaining("support_access_grants_scope_chk");
    }

    @Test
    void configOnlyGrantDoesNotOpenPatientRecords() {
        SeededTenant tenant = seedTenant();
        String token = token("support_engineer");
        Map<String, Object> g = grant(token, body(tenant.id(), seedTicket(tenant.id()), "config_only", 30));
        ResponseEntity<Map> view = exchange("/v1/platform/support-access/grants/" + g.get("id") + "/patients/" + UUID.randomUUID(),
                HttpMethod.GET, authed(token), Map.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(view.getBody().get("type")).asString().contains("scope-not-permitted");
    }

    @Test
    void grantRequestNeedsSupportAccessRequest() {
        SeededTenant tenant = seedTenant();
        ResponseEntity<Map> resp = exchange("/v1/platform/support-access/grants", HttpMethod.POST,
                authedJsonBody(token("compliance_dpo"), body(tenant.id(), seedTicket(tenant.id()), "config_only", 10)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- AC5: EXECUTE revoked from nabd_app ------------------------------------------------------------------

    @Test
    void nabdAppHoldsNoExecuteOnSearchAuditLog() {
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        for (String fn : List.of(SEARCH_11, SEARCH_7)) {
            assertThat(su.queryForObject("SELECT has_function_privilege('nabd_app', ?, 'EXECUTE')", Boolean.class, fn))
                    .as(fn).isFalse();
            assertThat(su.queryForObject("SELECT has_function_privilege('public', ?, 'EXECUTE')", Boolean.class, fn))
                    .as(fn).isFalse();
        }
        assertThat(su.queryForObject("SELECT has_function_privilege('nabd_platform', ?, 'EXECUTE')", Boolean.class, SEARCH_11)).isTrue();
        // membership is NOINHERIT: the app connection cannot call it without an explicit SET ROLE
        assertThat(su.queryForObject("SELECT inherit_option FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid "
                + "JOIN pg_roles a ON a.oid = m.member WHERE r.rolname = 'nabd_platform' AND a.rolname = 'nabd_app'", Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbc.queryForList("SELECT id FROM search_audit_log(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1)"))
                .hasStackTraceContaining("permission denied for function search_audit_log");
        // and the console still works through the platform role, leaving the pooled connection as nabd_app
        assertThat(exchange("/v1/platform/audit-log?limit=1", HttpMethod.GET, authed(token("compliance_dpo")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 25; i++) {
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("nabd_app");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void record(UUID tenantId, String action, Map<String, Object> after) {
        auditService.record(tenantId, "system", null, "Nabd System", "System", null, action, "patient",
                UUID.randomUUID(), null, after);
    }

    private SeededOperator operator(String role) {
        return seedOperator("p56-" + role + "-" + UUID.randomUUID() + "@nabd.health", role, false);
    }

    private String token(String role) {
        return platformLoginAndGetAccessToken(operator(role));
    }

    private Map<String, Object> page(String token, String path) {
        ResponseEntity<Map> resp = exchange(path, HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) resp.getBody().get("page");
    }

    private Map<String, Object> first(String token, String path) {
        ResponseEntity<Map> resp = exchange(path, HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) resp.getBody().get("data")).get(0);
    }

    private Map<String, Object> lastOperatorRow(UUID operatorId) {
        return jdbc.queryForMap("SELECT action, target_tenant_id, params::text AS params, row_count, reason, created_at "
                + "FROM master.operator_audit_log WHERE operator_id = ? ORDER BY id DESC LIMIT 1", operatorId);
    }

    private Map<String, Object> body(UUID tenantId, UUID ticketId, String scope, Integer minutes) {
        Map<String, Object> m = new HashMap<>();
        m.put("tenantId", tenantId.toString());
        if (ticketId != null) m.put("ticketId", ticketId.toString());
        if (scope != null) m.put("scope", scope);
        if (minutes != null) m.put("minutes", minutes);
        m.put("reason", "Ticket follow-up");
        return m;
    }

    private void assertGrantRefused(String token, Map<String, Object> body, String code) {
        ResponseEntity<Map> resp = exchange("/v1/platform/support-access/grants", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(resp.getStatusCode()).as(code).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("code")).isEqualTo(code);
    }

    private Map<String, Object> grant(String token, Map<String, Object> body) {
        ResponseEntity<Map> resp = exchange("/v1/platform/support-access/grants", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private long windowMinutes(Map<String, Object> grant) {
        return ((Number) grant.get("minutes")).longValue(); // PLT-057: the requested window; it starts at the owner's approval
    }

    // master.support_tickets carries no RLS (operator plane), so the app connection can seed it directly.
    private UUID seedTicket(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO master.support_tickets (id, tenant_id, source, raised_by_name, raised_by_role, subject, "
                + "description, sla_due_at) VALUES (?, ?, 'system', 'Nabd System', 'System', 'Grant test', 'd', now() + interval '1 day')",
                id, tenantId);
        return id;
    }
}
