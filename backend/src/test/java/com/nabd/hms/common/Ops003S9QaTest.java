package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S9 independent QA of OPS-003 (database side). Everything is derived from the catalog / the roles themselves, not from
 * the engineer's helper functions, and runs as the real nabd_app / nabd_reports roles.
 */
class Ops003S9QaTest extends ApiTestBase {

    private static final Set<String> STORY_EXEMPT = Set.of("public.tenant_lifecycle_events", "master.support_tickets",
            "master.support_access_grants", "master.subscriptions", "master.discount_requests");

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static final String TENANT_TABLES = """
            SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
            WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('pg_catalog','information_schema') ORDER BY 1""";

    private <T> T asTenant(UUID own, String visibleCsv, Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true) || set_config('app.visible_tenant_ids', ?, true)",
                    String.class, own.toString(), visibleCsv);
            return work.get();
        });
    }

    // ---- criterion 1/2: catalog invariant, independent query -------------------------------------------------

    @Test
    void everyTenantIdTableIsForcedWithFourPerCommandPoliciesAndNothingElse() {
        JdbcTemplate su = su();
        List<String> tables = su.queryForList(TENANT_TABLES, String.class);
        assertThat(tables.size()).as("tenant_id tables").isGreaterThan(60);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String t : tables) {
            if (STORY_EXEMPT.contains(t)) continue;
            checked++;
            Map<String, Object> flags = su.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", t);
            if (!(Boolean) flags.get("relrowsecurity") || !(Boolean) flags.get("relforcerowsecurity")) {
                problems.add(t + " not enabled+forced");
            }
            List<Map<String, Object>> pol = su.queryForList("""
                    SELECT polname, polcmd::text AS cmd, polpermissive, pg_get_expr(polqual, polrelid) AS q,
                           pg_get_expr(polwithcheck, polrelid) AS w FROM pg_policy WHERE polrelid = ?::regclass""", t);
            Set<String> cmds = new TreeSet<>();
            for (Map<String, Object> p : pol) {
                String cmd = (String) p.get("cmd");
                cmds.add(cmd);
                String q = (String) p.get("q");
                String w = (String) p.get("w");
                if (!(Boolean) p.get("polpermissive")) problems.add(t + " restrictive policy " + p.get("polname"));
                if ("*".equals(cmd)) problems.add(t + " FOR ALL policy " + p.get("polname"));
                if (("a".equals(cmd) || "w".equals(cmd)) && (w == null || !w.contains("tenant_id"))) problems.add(t + " " + cmd + " lacks tenant WITH CHECK");
                if (("r".equals(cmd) || "d".equals(cmd) || "w".equals(cmd)) && (q == null || !q.contains("tenant_id"))) problems.add(t + " " + cmd + " lacks tenant USING");
                if ("true".equals(q) || "true".equals(w)) problems.add(t + " permissive true policy");
            }
            if (!cmds.equals(Set.of("r", "a", "w", "d"))) problems.add(t + " commands covered " + cmds);
        }
        assertThat(checked).isGreaterThan(55);
        assertThat(problems).isEmpty();
    }

    @Test
    void exemptionFileIsExactlyTheFiveReviewedOperatorPlaneTablesAndEachIsReal() {
        assertThat(RlsSelfCheck.exemptions()).containsExactlyInAnyOrderElementsOf(STORY_EXEMPT);
        JdbcTemplate su = su();
        for (String t : STORY_EXEMPT) {
            assertThat(su.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid = ?::regclass AND attname = 'tenant_id'", Integer.class, t))
                    .as(t + " exists with tenant_id (no stale exemption)").isEqualTo(1);
        }
    }

    @Test
    void theFiveNamedTablesAreCoveredAsStated() {
        JdbcTemplate su = su();
        for (String t : List.of("login_attempts", "workflow_definitions", "package_eligible_doctors")) {
            assertThat(su.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", Boolean.class, t)).as(t).isTrue();
            assertThat(su.queryForObject("SELECT count(DISTINCT polcmd) FROM pg_policy WHERE polrelid = ?::regclass AND polcmd <> '*'", Integer.class, t)).as(t + " per-command").isEqualTo(4);
        }
        for (String t : List.of("legal_entities", "tax_registrations")) {
            assertThat(su.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", Boolean.class, t)).as(t).isTrue();
            for (String role : List.of("nabd_app")) {
                for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    assertThat(su.queryForObject("SELECT has_table_privilege(?, ?::regclass, ?)", Boolean.class, role, t, priv))
                            .as(role + " " + priv + " on " + t).isFalse();
                }
            }
        }
    }

    @Test
    void ownerLevelTablesShowNoRowsToTheReportsRoleEither() throws Exception {
        JdbcTemplate su = su();
        SeededOwner o = seedOwner("s9qa-le@a.com");
        UUID le = UUID.randomUUID();
        su.update("INSERT INTO legal_entities (id, owner_id, name, entity_type) VALUES (?, ?, 'x', 'company')", le, o.id());
        try (Connection c = dataSourceFor(REPORTS_ROLE, REPORTS_ROLE_PW).getConnection(); Statement st = c.createStatement()) {
            for (String t : List.of("legal_entities", "tax_registrations")) {
                try {
                    var rs = st.executeQuery("SELECT count(*) FROM " + t);
                    rs.next();
                    assertThat(rs.getInt(1)).as(t + " rows visible to nabd_reports").isZero();
                } catch (SQLException permissionDenied) {
                    assertThat(permissionDenied.getMessage()).contains("permission denied");
                }
            }
        } finally {
            su.update("DELETE FROM legal_entities WHERE id = ?", le);
        }
    }

    @Test
    void workflowDefinitionsPlatformRowsAreReadOnlyForATenantAndPackageDoctorsAreTenantScoped() {
        SeededTenant a = seedTenant();
        UUID tpl = UUID.randomUUID();
        su().update("INSERT INTO workflow_definitions (id, code, version, tenant_id, name, steps) VALUES (?, ?, 1, NULL, 'Platform tpl', '[\"billing\"]')",
                tpl, "s9qa-" + tpl);
        assertThat(asTenant(a.id(), a.id().toString(), () -> jdbc.queryForObject("SELECT count(*) FROM workflow_definitions WHERE id = ?", Integer.class, tpl)))
                .as("tenant reads platform template").isEqualTo(1);
        assertThat(asTenant(a.id(), a.id().toString(), () -> {
            try {
                return jdbc.update("UPDATE workflow_definitions SET name = 'hijack' WHERE id = ?", tpl);
            } catch (DataAccessException e) {
                return 0;
            }
        })).as("tenant cannot update platform template").isZero();
        assertThat(asTenant(a.id(), a.id().toString(), () -> jdbc.update("DELETE FROM workflow_definitions WHERE id = ?", tpl))).isZero();
        assertThatThrownBy(() -> asTenant(a.id(), a.id().toString(), () -> jdbc.update(
                "INSERT INTO workflow_definitions (code, version, tenant_id, name, steps) VALUES ('s9qa-new', 1, NULL, 'x', '[]')")))
                .rootCause().hasMessageContaining("row-level security");
        assertThat(su().queryForObject("SELECT name FROM workflow_definitions WHERE id = ?", String.class, tpl)).isEqualTo("Platform tpl");
        su().update("DELETE FROM workflow_definitions WHERE id = ?", tpl);
        // package_eligible_doctors has its own NOT NULL tenant_id
        assertThat(su().queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name='package_eligible_doctors' AND column_name='tenant_id'", String.class)).isEqualTo("NO");
    }

    // ---- criterion 3: generic cross-tenant sweep over every tenant table -----------------------------------------

    @Test
    void noTableLetsTenantAReadWriteMoveOrCloneTenantBRowsEvenWithWidenedVisibility() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        SeededStaff sa = seedStaff(a, seedFullAccessRole(a.id()), "s9a@a.com", "+919000003001", false);
        SeededStaff sb = seedStaff(b, seedFullAccessRole(b.id()), "s9b@a.com", "+919000003002", false);
        loginAndGetAccessToken(sa);
        loginAndGetAccessToken(sb);
        for (SeededTenant t : List.of(a, b)) {
            inTenantTx(t.id(), () -> jdbc.update(
                    "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'P', '+919000003099', '1990-01-01', 'other')", t.id()));
        }
        JdbcTemplate su = su();
        List<String> tables = su.queryForList(TENANT_TABLES, String.class);
        Map<String, Map<String, Long>> before = snapshot(su, tables, a.id(), b.id());
        long withBothRows = before.values().stream().filter(m -> m.getOrDefault(a.id().toString(), 0L) > 0 && m.getOrDefault(b.id().toString(), 0L) > 0).count();
        assertThat(withBothRows).as("tables where both tenants have rows (sweep is not vacuous)").isGreaterThanOrEqualTo(6);

        List<String> leaks = new ArrayList<>();
        for (String t : tables) {
            if (STORY_EXEMPT.contains(t) || !Boolean.TRUE.equals(su.queryForObject("SELECT has_table_privilege('nabd_app', ?::regclass, 'SELECT')", Boolean.class, t))) continue;
            for (String visible : List.of(a.id().toString(), a.id() + "," + b.id())) {
                boolean widened = visible.contains(",");
                if (!widened) {
                    Long seen = asTenant(a.id(), visible, () -> jdbc.queryForObject("SELECT count(*) FROM " + t + " WHERE tenant_id = ?", Long.class, b.id()));
                    if (seen != 0) leaks.add(t + ": A sees " + seen + " rows of B without widening");
                }
                String copy = "INSERT INTO " + t + " SELECT (json_populate_record(NULL::" + t + ", to_jsonb(x) || jsonb_build_object('tenant_id', ?::text))).* FROM " + t + " x WHERE x.tenant_id = ?";
                String[][] writes = {
                        {"update-b-row", "UPDATE " + t + " SET tenant_id = tenant_id WHERE tenant_id = '" + b.id() + "'"},
                        {"delete-b-row", "DELETE FROM " + t + " WHERE tenant_id = '" + b.id() + "'"},
                        {"move-a-row-to-b", "UPDATE " + t + " SET tenant_id = '" + b.id() + "' WHERE tenant_id = '" + a.id() + "'"},
                };
                for (String[] w : writes) {
                    try {
                        int n = asTenant(a.id(), visible, () -> jdbc.update(w[1]));
                        if (n != 0) leaks.add(t + " (" + (widened ? "widened" : "plain") + ") " + w[0] + " changed " + n + " rows");
                    } catch (DataAccessException expectedRefusal) {
                        // RLS error / missing privilege / trigger: refused, which is the point
                    }
                }
                try {
                    int n = asTenant(a.id(), visible, () -> jdbc.update(copy, b.id().toString(), a.id()));
                    if (n != 0) leaks.add(t + " (" + (widened ? "widened" : "plain") + ") cloned " + n + " rows into B");
                } catch (DataAccessException expectedRefusal) {
                    // refused
                }
            }
        }
        assertThat(snapshot(su, tables, a.id(), b.id())).as("row counts per tenant unchanged on every table").isEqualTo(before);
        assertThat(leaks).isEmpty();
    }

    private Map<String, Map<String, Long>> snapshot(JdbcTemplate su, List<String> tables, UUID a, UUID b) {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        for (String t : tables) {
            Map<String, Long> m = new TreeMap<>();
            su.query("SELECT tenant_id::text, count(*) FROM " + t + " WHERE tenant_id IN (?, ?) GROUP BY 1",
                    rs -> { m.put(rs.getString(1), rs.getLong(2)); }, a, b);
            out.put(t, m);
        }
        return out;
    }

    // ---- criterion 1: the app role cannot sidestep RLS --------------------------------------------------------------

    @Test
    void theAppRoleOwnsNothingBypassesNothingAndCannotSwitchRlsOff() throws Exception {
        JdbcTemplate su = su();
        Map<String, Object> r = su.queryForMap("SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolreplication FROM pg_roles WHERE rolname = 'nabd_app'");
        assertThat(r.values()).containsOnly(false);
        assertThat(su.queryForObject("SELECT count(*) FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = 'nabd_app')", Integer.class))
                .as("nabd_app is a member of no role").isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM pg_class WHERE relowner = (SELECT oid FROM pg_roles WHERE rolname = 'nabd_app')", Integer.class)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM pg_proc WHERE proowner = (SELECT oid FROM pg_roles WHERE rolname = 'nabd_app')", Integer.class)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspowner = (SELECT oid FROM pg_roles WHERE rolname = 'nabd_app')", Integer.class)).isZero();
        // every table grant: no TRUNCATE / TRIGGER / REFERENCES for the app role
        assertThat(su.queryForList("""
                SELECT n.nspname || '.' || c.relname || ':' || p FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN unnest(ARRAY['TRUNCATE','TRIGGER','REFERENCES']) p
                WHERE c.relkind IN ('r','p') AND n.nspname IN ('public','master') AND has_table_privilege('nabd_app', c.oid, p)""", String.class))
                .as("excess privileges").isEmpty();

        SeededTenant t = seedTenant();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'P', '+919000003098', '1990-01-01', 'other')", t.id()));
        try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection(); Statement st = c.createStatement()) {
            for (String sql : List.of("ALTER TABLE patients DISABLE ROW LEVEL SECURITY", "ALTER TABLE patients NO FORCE ROW LEVEL SECURITY",
                    "DROP POLICY t_select ON patients", "CREATE TABLE public.zz_s9qa_made_by_app (id int)", "SET ROLE nabd_migrator", "SET ROLE nabd_definer",
                    "TRUNCATE patients", "CREATE POLICY zz ON patients USING (true)", "ALTER ROLE nabd_app BYPASSRLS")) {
                assertThatThrownBy(() -> st.execute(sql)).as(sql).isInstanceOf(SQLException.class);
            }
            // row_security = off must make a policy-bound query ERROR, never return all rows
            st.execute("SET row_security = off");
            assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM patients")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    /** Hardening beyond the written criteria: "DML only" should not include the migration ledger. */
    @Test
    @org.junit.jupiter.api.Disabled("DEFECT S9QA OPS-003 D5: nabd_app can still write flyway_schema_history (hardening beyond criteria); fix in the V210 grants")
    void appRoleCannotRewriteTheMigrationLedger() {
        assertThat(su().queryForObject("SELECT has_table_privilege('nabd_app', 'flyway_schema_history', 'INSERT') OR has_table_privilege('nabd_app', 'flyway_schema_history', 'UPDATE') OR has_table_privilege('nabd_app', 'flyway_schema_history', 'DELETE')", Boolean.class))
                .as("nabd_app DML on flyway_schema_history").isFalse();
    }

    /** Criterion 7 names nabd_app only, but the read-only reports role must not be a side door to the same hashes. */
    @Test
    @org.junit.jupiter.api.Disabled("DEFECT S9QA OPS-003 D4: nabd_reports can read owners.pin_hash; revoke in V210-style migration")
    void reportsRoleCannotReadOwnerPinHashEither() {
        assertThat(su().queryForObject("SELECT has_column_privilege('nabd_reports', 'owners', 'pin_hash', 'SELECT')", Boolean.class))
                .as("nabd_reports SELECT owners.pin_hash").isFalse();
    }

    @Test
    void securityDefinerFunctionsAreOwnedByDefinerRoleWithPinnedSearchPath() {
        List<String> bad = su().queryForList("""
                SELECT p.oid::regprocedure::text || ' owner=' || r.rolname || ' cfg=' || coalesce(p.proconfig::text, 'none')
                FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE p.prosecdef AND n.nspname IN ('public','master')
                  AND (r.rolname <> 'nabd_definer' OR p.proconfig IS NULL OR NOT (p.proconfig::text ILIKE '%search_path%'))""", String.class);
        assertThat(bad).isEmpty();
    }

    // ---- criterion 1: RlsSelfCheck names offenders ----------------------------------------------------------------

    @Test
    void selfCheckNamesUnforcedEnabledOnlyMasterAndPartitionedTablesAndRefusesToStart() {
        JdbcTemplate su = su();
        JdbcTemplate app = new JdbcTemplate(dataSourceFor(APP_ROLE, APP_ROLE_PW));
        Set<String> ex = RlsSelfCheck.exemptions();
        assertThat(RlsSelfCheck.violations(app, ex)).as("clean baseline").isEmpty();
        try {
            su.execute("CREATE TABLE public.zz_s9qa_plain (id int, tenant_id uuid)");
            su.execute("CREATE TABLE public.zz_s9qa_enabled_only (id int, tenant_id uuid)");
            su.execute("ALTER TABLE public.zz_s9qa_enabled_only ENABLE ROW LEVEL SECURITY");
            su.execute("CREATE TABLE master.zz_s9qa_master (id int, tenant_id uuid)");
            su.execute("CREATE TABLE public.zz_s9qa_part (id int, tenant_id uuid) PARTITION BY HASH (tenant_id)");
            su.execute("CREATE TABLE public.zz_s9qa_forced (id int, tenant_id uuid)");
            su.execute("ALTER TABLE public.zz_s9qa_forced ENABLE ROW LEVEL SECURITY");
            su.execute("ALTER TABLE public.zz_s9qa_forced FORCE ROW LEVEL SECURITY");
            String all = String.join(" | ", RlsSelfCheck.violations(app, ex));
            assertThat(all).contains("public.zz_s9qa_plain", "public.zz_s9qa_enabled_only", "master.zz_s9qa_master", "public.zz_s9qa_part")
                    .doesNotContain("zz_s9qa_forced");
            assertThatThrownBy(() -> new RlsSelfCheck(app).run(null)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refusing to start").hasMessageContaining("public.zz_s9qa_plain");
            // an app role that owns a table is flagged even if the table is forced
            su.execute("ALTER TABLE public.zz_s9qa_forced OWNER TO nabd_app");
            assertThat(String.join(" | ", RlsSelfCheck.violations(app, ex))).contains("owns tables").contains("zz_s9qa_forced");
        } finally {
            for (String t : List.of("public.zz_s9qa_plain", "public.zz_s9qa_enabled_only", "master.zz_s9qa_master", "public.zz_s9qa_part", "public.zz_s9qa_forced")) {
                su.execute("DROP TABLE IF EXISTS " + t + " CASCADE");
            }
        }
        assertThat(RlsSelfCheck.violations(app, ex)).isEmpty();
        // a superuser / BYPASSRLS connection is refused
        assertThat(String.join(" ", RlsSelfCheck.violations(su, ex))).contains("superuser or has BYPASSRLS");
    }

    @Test
    void selfCheckCostsUnder200msOnWarmCatalog() {
        JdbcTemplate app = new JdbcTemplate(dataSourceFor(APP_ROLE, APP_ROLE_PW));
        Set<String> ex = RlsSelfCheck.exemptions();
        RlsSelfCheck.violations(app, ex);
        long t0 = System.nanoTime();
        RlsSelfCheck.violations(app, ex);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("ms for the three catalog queries").isLessThan(200);
    }

    /** The story says "every table with a tenant_id column"; the check only looks at schemas public and master. */
    @Test
    @org.junit.jupiter.api.Disabled("DEFECT S9QA OPS-003: RlsSelfCheck only scans public and master schemas")
    void selfCheckAlsoSeesATenantTableInAnyOtherSchema() {
        JdbcTemplate su = su();
        JdbcTemplate app = new JdbcTemplate(dataSourceFor(APP_ROLE, APP_ROLE_PW));
        try {
            su.execute("CREATE SCHEMA zz_s9qa");
            su.execute("CREATE TABLE zz_s9qa.t (id int, tenant_id uuid)");
            assertThat(String.join(" | ", RlsSelfCheck.violations(app, RlsSelfCheck.exemptions()))).contains("zz_s9qa.t");
        } finally {
            su.execute("DROP SCHEMA IF EXISTS zz_s9qa CASCADE");
        }
    }

    // ---- criterion 7: owner pin_hash ------------------------------------------------------------------------------------

    @Test
    void appRoleCannotReadPinHashButOwnerSignInWorksThroughTheLookup() throws Exception {
        SeededOwner owner = seedOwner("s9qa-owner@a.com");
        try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("SELECT pin_hash FROM owners")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.executeQuery("SELECT * FROM owners")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.executeQuery("SELECT o.pin_hash FROM owners o WHERE o.id IS NOT NULL")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE owners SET pin_hash = 'x' WHERE id IS NOT NULL AND pin_hash IS NOT NULL")).hasMessageContaining("permission denied");
            assertThat(st.executeQuery("SELECT id, email FROM owners").next()).as("non-secret columns stay readable").isTrue();
            var rs = st.executeQuery("SELECT count(*) FROM owner_auth_lookup(p_email => 's9qa-owner@a.com')");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            for (String args : List.of("", "p_email => 's9qa-owner@a.com', p_id => '" + owner.id() + "'", "p_invite_token_hash => 'nope'")) {
                var r2 = st.executeQuery("SELECT count(*) FROM owner_auth_lookup(" + args + ")");
                r2.next();
                assertThat(r2.getInt(1)).as("lookup(" + args + ") must not enumerate").isZero();
            }
        }
        JdbcTemplate su = su();
        assertThat(su.queryForObject("SELECT has_function_privilege('nabd_reports', 'owner_auth_lookup(citext, uuid, text)', 'EXECUTE')", Boolean.class)).isFalse();
        assertThat(su.queryForObject("SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = 'owner_auth_lookup'", String.class)).isEqualTo("nabd_definer");
        // end to end over HTTP
        assertThat(ownerLoginPendingToken(owner)).isNotBlank();
        ResponseEntity<Map> bad = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of("email", owner.email(), "pin", "0000")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> unknown = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of("email", "nobody@a.com", "pin", "1234")), Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- criterion 4: timeouts at role level --------------------------------------------------------------------------

    @Test
    void roleLevelTimeoutsAreSetOnTheRolesNotTheSession() throws Exception {
        JdbcTemplate su = su();
        Map<String, String> app = settings(su, "nabd_app");
        assertThat(app).containsEntry("statement_timeout", "5s").containsEntry("lock_timeout", "3s").containsEntry("idle_in_transaction_session_timeout", "15s");
        assertThat(settings(su, "nabd_reports")).containsEntry("statement_timeout", "60s");
        try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery("SHOW statement_timeout");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("5s");
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> st.executeQuery("SELECT pg_sleep(8)")).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("57014"));
            assertThat((System.nanoTime() - t0) / 1_000_000_000.0).as("cancelled at ~5 s").isBetween(4.5, 6.5);
        }
        try (Connection c = dataSourceFor(REPORTS_ROLE, REPORTS_ROLE_PW).getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery("SHOW statement_timeout");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("1min");
            st.executeQuery("SELECT pg_sleep(6)"); // would be cancelled for nabd_app
            assertThatThrownBy(() -> st.executeUpdate("UPDATE patients SET name = name")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM audit_events")).isInstanceOf(SQLException.class);
        }
    }

    private Map<String, String> settings(JdbcTemplate su, String role) {
        Map<String, String> out = new TreeMap<>();
        su.queryForList("SELECT unnest(setconfig) FROM pg_db_role_setting WHERE setrole = (SELECT oid FROM pg_roles WHERE rolname = ?)", String.class, role)
                .forEach(kv -> out.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1)));
        return out;
    }

    @Test
    void lockTimeoutCancelsAfterAboutThreeSecondsAndIdleInTransactionIsKilledAfterFifteen() throws Exception {
        SeededTenant t = seedTenant();
        UUID pid = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?, ?, 'L', '+919000003097', '1990-01-01', 'other')", pid, t.id()));
        try (Connection holder = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection(); Connection waiter = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection()) {
            for (Connection c : List.of(holder, waiter)) {
                c.setAutoCommit(false);
                try (var ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    ps.setString(1, t.id().toString());
                    ps.execute();
                }
            }
            try (Statement h = holder.createStatement(); Statement w = waiter.createStatement()) {
                h.executeQuery("SELECT 1 FROM patients WHERE id = '" + pid + "' FOR UPDATE");
                long t0 = System.nanoTime();
                assertThatThrownBy(() -> w.executeUpdate("UPDATE patients SET name = 'W' WHERE id = '" + pid + "'")).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("55P03"));
                assertThat((System.nanoTime() - t0) / 1_000_000_000.0).as("lock_timeout seconds").isBetween(2.5, 4.5);
            }
            waiter.rollback();
            // idle in transaction: holder keeps the open tx; after >15 s the server terminates the session
            Thread.sleep(16_500);
            try (Statement h = holder.createStatement()) {
                assertThatThrownBy(() -> h.executeQuery("SELECT 1")).isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void roleBootstrapAndMigrateJobAreIdempotentAndTheLiteralPasswordsAreNotInTheFile() throws Exception {
        String raw = java.nio.file.Files.readString(java.nio.file.Path.of("../infra/db/bootstrap-roles.sql"));
        assertThat(raw).contains(":'migrator_pw'", ":'app_pw'", ":'reports_pw'").doesNotContainPattern("(?i)password\\s+'[^']");
        String sql = raw.replace(":'migrator_pw'", "'" + MIGRATOR_ROLE_PW + "'").replace(":'app_pw'", "'" + APP_ROLE_PW + "'")
                .replace(":'reports_pw'", "'" + REPORTS_ROLE_PW + "'");
        su().execute(sql); // second run: no error, settings intact, app role still connects
        assertThat(settings(su(), "nabd_app")).containsEntry("statement_timeout", "5s");
        try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }
        // the migrate job run again against the migrated database is a no-op and does not need superuser
        MigrateMain.migrate(superuserDataSource().getUrl(), MIGRATOR_ROLE, MIGRATOR_ROLE_PW, "filesystem:../db/migrations");
        // and the migrator is not a superuser / bypass role either (FORCE binds it)
        assertThat(su().queryForMap("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'nabd_migrator'").values()).containsOnly(false);
    }

    @Test
    void hikariSettingsAreExplicitAndWithinTheStatedBounds() {
        com.zaxxer.hikari.HikariDataSource ds = (com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource();
        assertThat(ds.getMaximumPoolSize()).isBetween(15, 20);
        assertThat(ds.getMinimumIdle()).isEqualTo(ds.getMaximumPoolSize());
        assertThat(ds.getConnectionTimeout()).isEqualTo(3000);
        assertThat(ds.getMaxLifetime()).isEqualTo(30 * 60_000L);
        assertThat(ds.getLeakDetectionThreshold()).isEqualTo(10_000);
    }
}
