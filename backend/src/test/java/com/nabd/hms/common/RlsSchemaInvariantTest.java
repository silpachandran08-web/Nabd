package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-003 criteria 1 and 2: the catalog itself proves tenant isolation is forced, per command, with WITH CHECK. */
class RlsSchemaInvariantTest extends ApiTestBase {

    private static final String TENANT_TABLES = """
            SELECT n.nspname || '.' || c.relname AS tbl, c.relname, c.relrowsecurity, c.relforcerowsecurity
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
            WHERE c.relkind IN ('r','p') AND n.nspname IN ('public','master') ORDER BY 1""";

    @Test
    void everyTenantIdTableIsForcedWithPerCommandPoliciesAndWithCheck() {
        Set<String> exempt = RlsSelfCheck.exemptions();
        List<Map<String, Object>> tables = jdbc.queryForList(TENANT_TABLES);
        assertThat(tables).as("catalog query found the tenant tables").hasSizeGreaterThan(40);
        for (Map<String, Object> t : tables) {
            String tbl = (String) t.get("tbl");
            if (exempt.contains(tbl)) {
                continue;
            }
            assertThat((Boolean) t.get("relrowsecurity")).as(tbl + " relrowsecurity").isTrue();
            assertThat((Boolean) t.get("relforcerowsecurity")).as(tbl + " relforcerowsecurity").isTrue();
            List<Map<String, Object>> pols = jdbc.queryForList(
                    "SELECT cmd, qual, with_check FROM pg_policies WHERE schemaname || '.' || tablename = ?", tbl);
            Map<String, Map<String, Object>> byCmd = pols.stream().collect(Collectors.toMap(p -> (String) p.get("cmd"), p -> p));
            assertThat(byCmd.keySet()).as(tbl + " has one policy per command and no FOR ALL").containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE", "DELETE");
            assertThat(byCmd.get("INSERT").get("with_check")).as(tbl + " INSERT WITH CHECK").isNotNull();
            assertThat(byCmd.get("UPDATE").get("with_check")).as(tbl + " UPDATE WITH CHECK").isNotNull();
            assertThat(byCmd.get("SELECT").get("qual")).as(tbl + " SELECT USING").isNotNull();
            assertThat(byCmd.get("DELETE").get("qual")).as(tbl + " DELETE USING").isNotNull();
        }
    }

    @Test
    void theNamedRemainingTablesAreCovered() {
        for (String t : List.of("login_attempts", "workflow_definitions", "package_eligible_doctors")) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", t);
            assertThat(row).as(t).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = ?", Integer.class, t)).as(t + " policies").isEqualTo(4);
        }
        // owner-level tables: forced deny-all and no grant for the app role
        for (String t : List.of("legal_entities", "tax_registrations")) {
            assertThat(jdbc.queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", Boolean.class, t)).as(t).isTrue();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('nabd_app', ?, 'SELECT')", Boolean.class, t)).as(t + " app grant").isFalse();
        }
    }

    @Test
    void workflowDefinitionsPlatformRowsAreReadableButNeverWritableByATenant() {
        SeededTenant a = seedTenant(); // platform templates ('clinic_walkin', ...) are seeded by V40 with tenant_id NULL
        Integer visible = inTenantTx(a.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM workflow_definitions WHERE tenant_id IS NULL AND code = 'clinic_walkin'", Integer.class));
        assertThat(visible).isEqualTo(1);
        Integer changed = inTenantTx(a.id(), () -> jdbc.update("UPDATE workflow_definitions SET name = 'x' WHERE code = 'clinic_walkin'"));
        assertThat(changed).as("a tenant UPDATE of a platform row hits nothing").isZero();
        Integer deleted = inTenantTx(a.id(), () -> jdbc.update("DELETE FROM workflow_definitions WHERE code = 'clinic_walkin'"));
        assertThat(deleted).isZero();
    }

    @Test
    void selfCheckPassesForTheAppRole() {
        assertThat(RlsSelfCheck.violations(jdbc, RlsSelfCheck.exemptions())).isEmpty();
    }

    @Test
    void selfCheckNamesAnUnforcedTenantTableAndABypassingRole() throws Exception {
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("CREATE TABLE public.zz_ops003_probe (tenant_id uuid)");
            try {
                Set<String> known = RlsSelfCheck.exemptions();
                List<String> problems = RlsSelfCheck.violations(jdbc, known);
                assertThat(problems).hasSize(1);
                assertThat(problems.get(0)).contains("zz_ops003_probe").contains("FORCE ROW LEVEL SECURITY");
                // the exemption list is the only way out
                Set<String> withProbe = new java.util.HashSet<>(known);
                withProbe.add("public.zz_ops003_probe");
                assertThat(RlsSelfCheck.violations(jdbc, withProbe)).isEmpty();
            } finally {
                st.execute("DROP TABLE public.zz_ops003_probe");
            }
        }
        List<String> asSuper = RlsSelfCheck.violations(new JdbcTemplate(superuserDataSource()), RlsSelfCheck.exemptions());
        assertThat(asSuper).anyMatch(p -> p.contains("superuser or has BYPASSRLS"));
        List<String> asMigrator = RlsSelfCheck.violations(new JdbcTemplate(dataSourceFor(MIGRATOR_ROLE, MIGRATOR_ROLE_PW)), RlsSelfCheck.exemptions());
        assertThat(asMigrator).anyMatch(p -> p.contains("owns tables")); // the old single-role topology would fail to boot
    }

    @Test
    void securityDefinerFunctionsAreOwnedByTheBypassRlsRole() {
        List<String> offenders = jdbc.queryForList("""
                SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                JOIN pg_roles r ON r.oid = p.proowner
                WHERE p.prosecdef AND n.nspname IN ('public','master') AND NOT (r.rolbypassrls OR r.rolsuper)""", String.class);
        assertThat(offenders).as("SECURITY DEFINER functions with an RLS-bound owner; end the migration with SELECT nabd_adopt_definer_functions()").isEmpty();
    }
}
