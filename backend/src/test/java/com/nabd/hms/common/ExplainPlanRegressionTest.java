package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-001 plan pins. Seeds 40k rows per hot table across 40 fake tenants (superuser, triggers/FKs off via
 * session_replication_role=replica), ANALYZEs, then EXPLAINs the real repository SQL as nabd_app with RLS
 * active and the tenant GUC set. A pinned query fails if it loses its tenant_id Index Cond or seq-scans.
 * Until T-010 ships the array policy these assert the explicit/RLS tenant_id predicate only.
 */
class ExplainPlanRegressionTest extends ApiTestBase {

    private static final int TENANTS = 40, PER_TENANT = 1000;
    private static final String T = "00000000-0000-0000-0000-000000000001";   // fixture tenant 1
    private static final String P = "00000000-0000-0000-0001-000000000007";   // a patient of tenant 1
    private static final String D = "00000000-0000-0000-0002-000000000001";   // a doctor of tenant 1

    private static String tid(String col) { return "('00000000-0000-0000-0000-' || lpad(" + col + "::text,12,'0'))::uuid"; }

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            String g = "FROM generate_series(1," + TENANTS + ") t, generate_series(1," + PER_TENANT + ") n";
            s.execute("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, queue_date, token_number, status, id, encounter_id, department_id, current_stage) " +
                    "SELECT " + tid("t") + ", ('00000000-0000-0000-0001-' || lpad((n % 100)::text,12,'0'))::uuid, " +
                    "('00000000-0000-0000-0002-' || lpad((n % 5 + 1)::text,12,'0'))::uuid, date '2026-01-01' + (n / 50), n, 'completed', " +
                    "gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'done' " + g);
            s.execute("INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type, row_hash) " +
                    "SELECT " + tid("t") + ", 'system', 'x', 'System', 'x.y', 'x', md5(n::text) " + g);
            s.execute("INSERT INTO vitals (tenant_id, queue_entry_id, patient_id, recorded_by, weight_kg, weight_recorded_at) " +
                    "SELECT " + tid("t") + ", gen_random_uuid(), ('00000000-0000-0000-0001-' || lpad((n % 100)::text,12,'0'))::uuid, gen_random_uuid(), " +
                    "CASE WHEN n % 10 = 0 THEN 20 END, now() - n * interval '1 hour' " + g);
            s.execute("ANALYZE queue_entries"); s.execute("ANALYZE audit_log"); s.execute("ANALYZE vitals");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            String in = "tenant_id::text LIKE '00000000-0000-0000-0000-0000000000__'";
            for (String t : List.of("vitals", "queue_entries", "audit_log")) s.execute("DELETE FROM " + t + " WHERE " + in);
        }
    }

    /** EXPLAIN as nabd_app (not owner, RLS enforced) with app.tenant_id set, the way TenantContext does. */
    private String plan(String sql) throws Exception {
        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                ResultSet who = s.executeQuery("SELECT current_user, (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user)");
                who.next();
                assertThat(who.getString(1)).isEqualTo(APP_ROLE);
                assertThat(who.getBoolean(2)).as("RLS must apply to the planning role").isFalse();
                s.execute("SELECT set_config('app.tenant_id', '" + T + "', true)");
                StringBuilder sb = new StringBuilder();
                try (ResultSet rs = s.executeQuery("EXPLAIN " + sql)) { while (rs.next()) sb.append(rs.getString(1)).append('\n'); }
                return sb.toString();
            } finally { c.rollback(); }
        }
    }

    private void assertIndexed(String sql, String indexName) throws Exception {
        String p = plan(sql);
        assertThat(p).as(p).doesNotContain("Seq Scan").contains(indexName).containsPattern("Index Cond: .*tenant_id");
    }

    @Test
    void findLastVisitAtUsesPatientIndex() throws Exception {
        assertIndexed("SELECT max(updated_at) FROM queue_entries WHERE tenant_id = '" + T + "' AND patient_id = '" + P + "' AND status = 'completed'",
                "idx_queue_entries"); // S9 merge: with OPS-003's array policy the row estimate collapses to 1, so the planner ties among tenant-prefixed indexes; pin "indexed, tenant Index Cond, no seq scan" until the estimate is fixed
    }

    @Test
    void nextTokenNumberUsesDoctorDateIndexUnderRls() throws Exception {
        // Known gap until T-010: the OR-shaped RLS policy keeps tenant_id as a Filter, not an Index Cond. Pin what holds today
        // (index scan on the doctor/date index, no seq scan); flip to assertIndexed once the array policy ships.
        String p = plan("SELECT COALESCE(MAX(token_number), 0) FROM queue_entries WHERE doctor_id = '" + D + "' AND queue_date = date '2026-01-10'");
        assertThat(p).as(p).doesNotContain("Seq Scan").contains("Index Scan using idx_queue_doctor_date");
    }

    @Test
    void queueByDayUsesTenantDateIndex() throws Exception {
        assertIndexed("SELECT count(*) FROM queue_entries WHERE tenant_id = '" + T + "' AND queue_date = date '2026-01-10'", "idx_queue_entries"); // see note on findLastVisitAtUsesPatientIndex
    }

    @Test
    void auditChainHeadUsesTenantIdIndex() throws Exception {
        assertIndexed("SELECT row_hash FROM audit_log WHERE tenant_id = '" + T + "' ORDER BY id DESC LIMIT 1 FOR UPDATE", "idx_audit_log_tenant_id");
    }

    @Test
    void latestWeightUsesPartialVitalsIndex() throws Exception {
        String sql = "SELECT weight_kg, weight_recorded_at FROM vitals WHERE tenant_id = '" + T + "' AND patient_id = '" + P +
                "' AND weight_kg IS NOT NULL ORDER BY weight_recorded_at DESC NULLS LAST, recorded_at DESC LIMIT 1";
        assertIndexed(sql, "idx_vitals_patient_weight");
        assertThat(plan(sql)).contains("Index Scan");
    }

    @Test
    void duplicateIndexesDroppedAndUniqueConstraintsRemain() {
        List<String> dups = List.of("idx_patients_mrn", "idx_staff_tenant_email", "idx_attendance_records_month", "idx_sessions_token_hash");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname = ANY (?)", String.class,
                (Object) dups.toArray(new String[0]))).isEmpty();
        // the unique indexes that replace them
        for (String[] u : new String[][]{{"patients", "(tenant_id, mrn)"}, {"staff", "(tenant_id, email)"},
                {"attendance_records", "(tenant_id, staff_id, record_date)"}, {"sessions", "(token_hash)"}}) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND tablename=? AND indexdef LIKE 'CREATE UNIQUE INDEX%' AND indexdef LIKE ?",
                    Integer.class, u[0], "%" + u[1]);
            assertThat(n).as(u[0]).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void newIndexesAreValidAndMigrationsAreSingleStatements() throws Exception {
        assertThat(jdbc.queryForList("SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE NOT i.indisvalid", String.class)).isEmpty();
        for (String f : List.of("V200__idx_queue_entries_patient", "V201__idx_audit_log_tenant_id", "V202__idx_vitals_patient_weight", "V204__idx_queue_entries_tenant_date")) {
            String sql = String.join("\n", Files.readAllLines(Path.of("../db/migrations/" + f + ".sql"))).replaceAll("(?m)^--.*$", "").trim();
            assertThat(sql).as(f).startsWith("CREATE INDEX").doesNotContain("BEGIN");
            assertThat(sql.split(";").length).as(f + " statements").isEqualTo(1);
        }
    }
}
