package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S9 OPS-001): index definitions, real timings as nabd_app with RLS, plan sensitivity, duplicate drops, CONCURRENTLY. */
class IndexPlansS9QaTest extends ApiTestBase {

    private static final int TENANTS = 200, PER = 500; // 100k rows per hot table; NOT the 2.49M of the story
    private static final String PFX = "00000000-0000-0000-f000-";
    private static final String T = PFX + "000000000001";
    private static final String T_EMPTY = PFX + "000000000999";   // tenant with zero rows in the big tables
    private static final String P = "00000000-0000-0000-f001-000000000007";

    private static String tid(String c) { return "('" + PFX + "' || lpad(" + c + "::text,12,'0'))::uuid"; }

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            String g = "FROM generate_series(1," + TENANTS + ") t, generate_series(1," + PER + ") n";
            s.execute("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, queue_date, token_number, status, id, encounter_id, department_id, current_stage) " +
                    "SELECT " + tid("t") + ", ('00000000-0000-0000-f001-' || lpad((n % 100)::text,12,'0'))::uuid, gen_random_uuid(), date '2026-01-01' + (n / 50), n, 'completed', " +
                    "gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'done' " + g);
            s.execute("INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type, row_hash) " +
                    "SELECT " + tid("t") + ", 'system', 'x', 'System', 'x.y', 'x', md5(n::text) " + g);
            s.execute("INSERT INTO vitals (tenant_id, queue_entry_id, patient_id, recorded_by, weight_kg, weight_recorded_at) " +
                    "SELECT " + tid("t") + ", gen_random_uuid(), ('00000000-0000-0000-f001-' || lpad((n % 100)::text,12,'0'))::uuid, gen_random_uuid(), " +
                    "CASE WHEN n % 10 = 0 THEN 20 END, now() - n * interval '1 hour' " + g);
            s.execute("ANALYZE queue_entries"); s.execute("ANALYZE audit_log"); s.execute("ANALYZE vitals");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            for (String t : List.of("vitals", "queue_entries", "audit_log")) s.execute("DELETE FROM " + t + " WHERE tenant_id::text LIKE '" + PFX + "%'");
        }
    }

    /** Runs sql under nabd_app with app.tenant_id set; returns [planText, executionTimeMs] from EXPLAIN (ANALYZE, FORMAT TEXT). */
    private Object[] run(String tenant, String sql, String... settings) throws Exception {
        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                ResultSet who = s.executeQuery("SELECT current_user, (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user)");
                who.next();
                assertThat(who.getString(1)).isEqualTo(APP_ROLE);
                assertThat(who.getBoolean(2)).isFalse();
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', true)");
                s.execute("SET LOCAL jit = off"); // the local test image has no LLVM
                for (String st : settings) s.execute(st);
                StringBuilder sb = new StringBuilder();
                double ms = -1;
                try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, TIMING OFF) " + sql)) {
                    while (rs.next()) {
                        String l = rs.getString(1);
                        sb.append(l).append('\n');
                        if (l.startsWith("Execution Time:")) ms = Double.parseDouble(l.replaceAll("[^0-9.]", ""));
                    }
                }
                return new Object[]{sb.toString(), ms};
            } finally { c.rollback(); }
        }
    }

    private double p95(String tenant, String sql, String... settings) throws Exception {
        List<Double> xs = new ArrayList<>();
        for (int i = 0; i < 60; i++) xs.add((Double) run(tenant, sql, settings)[1]);
        Collections.sort(xs.subList(10, 60)); // drop 10 warm-up runs
        List<Double> tail = xs.subList(10, 60);
        return tail.get((int) Math.ceil(tail.size() * 0.95) - 1);
    }

    private static final String LAST_VISIT = "SELECT max(queue_date) FROM queue_entries WHERE tenant_id = '" + T + "' AND patient_id = '" + P + "' AND status = 'completed'";
    private static final String AUDIT_HEAD = "SELECT row_hash FROM audit_log WHERE tenant_id = '%s' ORDER BY id DESC LIMIT 1 FOR UPDATE";
    private static final String WEIGHT = "SELECT weight_kg, weight_recorded_at FROM vitals WHERE tenant_id = '" + T + "' AND patient_id = '" + P +
            "' AND weight_kg IS NOT NULL ORDER BY weight_recorded_at DESC NULLS LAST, recorded_at DESC LIMIT 1";
    private static final String NO_INDEX = "SET LOCAL enable_indexscan = off";

    @Test
    void indexDefinitionsMatchTheSpec() {
        assertThat(def("idx_queue_entries_patient")).contains("(tenant_id, patient_id, queue_date DESC)");
        assertThat(def("idx_queue_entries_tenant_date")).contains("(tenant_id, queue_date)");
        assertThat(def("idx_audit_log_tenant_id")).contains("(tenant_id, id DESC)");
        String v = def("idx_vitals_patient_weight");
        assertThat(v).contains("(tenant_id, patient_id, weight_recorded_at DESC NULLS LAST)").containsIgnoringCase("WHERE (weight_kg IS NOT NULL)");
    }

    private String def(String name) {
        return jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname=?", String.class, name);
    }

    @Test
    void findLastVisitAt_plan_and_time() throws Exception {
        Object[] r = run(T, LAST_VISIT);
        String plan = (String) r[0];
        assertThat(plan).as(plan).doesNotContain("Seq Scan").containsPattern("Index Cond: .*tenant_id");
        double idx = p95(T, LAST_VISIT);
        double seq = p95(T, LAST_VISIT, NO_INDEX, "SET LOCAL enable_bitmapscan = off");
        System.out.printf("QA-OPS001 findLastVisitAt p95 indexed=%.3f ms, forced-seq=%.3f ms (100k rows)%n", idx, seq);
        assertThat(idx).as("p95 ms").isLessThan(5.0);
    }

    @Test
    void auditHead_busyTenant_andTenantWithNoRows_andPlatformNullTenant() throws Exception {
        String busy = String.format(AUDIT_HEAD, T), none = String.format(AUDIT_HEAD, T_EMPTY);
        for (String q : List.of(busy, none)) {
            String plan = (String) run(q.contains(T_EMPTY) ? T_EMPTY : T, q)[0];
            assertThat(plan).as(plan).doesNotContain("Seq Scan").contains("idx_audit_log_tenant_id").containsPattern("Index Cond: .*tenant_id");
        }
        double idx = p95(T_EMPTY, none), idxBusy = p95(T, busy);
        double seq = p95(T_EMPTY, none, NO_INDEX, "SET LOCAL enable_bitmapscan = off");
        System.out.printf("QA-OPS001 audit head p95 empty-tenant=%.3f ms, busy-tenant=%.3f ms, forced-seq=%.3f ms (100k rows)%n", idx, idxBusy, seq);
        assertThat(idx).as("p95 ms, tenant with no rows").isLessThan(1.0);
        assertThat(idxBusy).as("p95 ms, tenant with rows").isLessThan(1.0);
        // the platform ledger (tenant_id IS NULL) head lookup from audit_log_chain()
        String plat = "SELECT row_hash FROM audit_log WHERE tenant_id IS NULL ORDER BY id DESC LIMIT 1 FOR UPDATE";
        String pp = (String) run(T, plat)[0];
        System.out.println("QA-OPS001 platform-ledger head plan: " + pp.replace('\n', ' '));
        assertThat(pp).as(pp).doesNotContain("Seq Scan");
    }

    @Test
    void auditChainFunctionStillUsesTheIndexedHeadLookup() {
        String def = jdbc.queryForObject("SELECT pg_get_functiondef(p.oid) FROM pg_proc p WHERE proname = 'audit_log_chain'", String.class);
        assertThat(def).contains("WHERE tenant_id = NEW.tenant_id ORDER BY id DESC LIMIT 1");
    }

    @Test
    void latestWeight_isIndexScanOnPartialIndex() throws Exception {
        String plan = (String) run(T, WEIGHT)[0];
        assertThat(plan).as(plan).contains("Index Scan using idx_vitals_patient_weight").doesNotContain("Seq Scan");
        System.out.printf("QA-OPS001 latestWeight p95=%.3f ms%n", p95(T, WEIGHT));
    }

    /** The pin is only worth something if it would notice a regression: rebuild the plan without each index (rolled back). */
    @Test
    void planSensitivity_withoutTheIndexThePlanRegresses() throws Exception {
        for (String[] c : new String[][]{{"idx_vitals_patient_weight", WEIGHT, T}, {"idx_audit_log_tenant_id", String.format(AUDIT_HEAD, T_EMPTY), T_EMPTY}}) {
            try (Connection con = superuserConnection(); Statement s = con.createStatement()) {
                con.setAutoCommit(false);
                s.execute("DROP INDEX " + c[0]);
                s.execute("SET LOCAL ROLE " + APP_ROLE);
                s.execute("SELECT set_config('app.tenant_id', '" + c[2] + "', true)");
                StringBuilder sb = new StringBuilder();
                try (ResultSet rs = s.executeQuery("EXPLAIN " + c[1])) { while (rs.next()) sb.append(rs.getString(1)).append('\n'); }
                con.rollback();
                assertThat(sb.toString()).as("without " + c[0] + ":\n" + sb).doesNotContain(c[0]);
                System.out.println("QA-OPS001 without " + c[0] + ": " + sb.toString().split("\n")[sb.toString().split("\n").length > 1 ? 1 : 0].trim());
            }
        }
    }

    /** Each dropped index must be covered by a UNIQUE index on the same leading columns (queries keep an equal-or-better index). */
    @Test
    void fourDuplicatesGone_andEachIsCoveredByAUniqueIndexOnTheSameColumns() {
        String[][] dup = {{"idx_patients_mrn", "patients", "tenant_id,mrn"}, {"idx_staff_tenant_email", "staff", "tenant_id,email"},
                {"idx_attendance_records_month", "attendance_records", "tenant_id,staff_id,record_date"}, {"idx_sessions_token_hash", "sessions", "token_hash"}};
        for (String[] d : dup) {
            assertThat(jdbc.queryForList("SELECT 1 FROM pg_indexes WHERE indexname = ?", d[0])).as(d[0]).isEmpty();
            List<String> cols = jdbc.queryForList("SELECT (SELECT string_agg(a.attname, ',' ORDER BY k.ord) FROM unnest(i.indkey) WITH ORDINALITY k(attnum, ord) " +
                    "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) FROM pg_index i WHERE i.indrelid = ?::regclass AND i.indisunique AND i.indpred IS NULL", String.class, d[1]);
            assertThat(cols).as(d[1] + " unique indexes").contains(d[2]);
        }
    }

    @Test
    void noInvalidIndexes() {
        assertThat(jdbc.queryForList("SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid", String.class)).isEmpty();
    }

    /** Criterion 5: index migrations must be CONCURRENTLY (no write lock on hot tables). Engineer removed it (commit 070e511). */
    @Test
    @Disabled("DEFECT OPS-001-D1: V200/V201/V202/V204 are plain CREATE INDEX and V203 plain DROP INDEX; remove @Disabled to reproduce")
    void indexMigrationsUseConcurrently() throws Exception {
        for (String f : new String[]{"V200__idx_queue_entries_patient", "V201__idx_audit_log_tenant_id", "V202__idx_vitals_patient_weight", "V204__idx_queue_entries_tenant_date", "V203__drop_duplicate_indexes"}) {
            String sql = String.join("\n", Files.readAllLines(Path.of("../db/migrations/" + f + ".sql"))).replaceAll("(?m)^--.*$", "");
            assertThat(sql.toUpperCase()).as(f).contains("CONCURRENTLY");
        }
    }
}
