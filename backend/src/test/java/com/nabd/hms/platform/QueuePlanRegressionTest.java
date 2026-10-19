package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-018 plan regression: nextTokenNumber's query shape (tenant_id + doctor_id + queue_date) must use
 * an index and stay fast on a 500k-row queue_entries fixture (was 933 ms with the OR-shaped policy).
 * Fixture rows are bulk-loaded as superuser with FK triggers off (session_replication_role=replica),
 * spread over 500 doctors x 100 days so the target (doctor, day) holds a realistic ~10 rows.
 */
class QueuePlanRegressionTest extends ApiTestBase {

    @Test
    void nextTokenNumberQueryUsesAnIndexAndIsFastOn500kRows() throws Exception {
        SeededTenant t = seedTenant();
        UUID doctor = UUID.randomUUID();
        UUID dept = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM departments LIMIT 1", UUID.class));
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, encounter_id, queue_date, " +
                    "token_number, status, current_stage) " +
                    "SELECT id, '" + t.id() + "', gen_random_uuid(), CASE WHEN n % 500 = 0 THEN '" + doctor + "'::uuid ELSE gen_random_uuid() END, '" + dept + "', id, " +
                    "date '2026-01-01' + (n % 100), n, 'completed', stage_for_status('completed') " +
                    "FROM (SELECT gen_random_uuid() AS id, n FROM generate_series(1, 500000) n) s");
            st.execute("ANALYZE queue_entries");
        }
        String sql = "SELECT COALESCE(MAX(token_number), 0) FROM queue_entries WHERE tenant_id = '" + t.id()
                + "' AND doctor_id = '" + doctor + "' AND queue_date = date '2026-01-01'";
        StringBuilder plan = new StringBuilder();
        double ms = inTenantTx(t.id(), () -> {
            jdbc.query("EXPLAIN (ANALYZE, FORMAT TEXT) " + sql, (ResultSet rs) -> { plan.append(rs.getString(1)).append('\n'); });
            // second run is the warm one the check-in lock actually sees
            plan.setLength(0);
            jdbc.query("EXPLAIN (ANALYZE, FORMAT TEXT) " + sql, (ResultSet rs) -> { plan.append(rs.getString(1)).append('\n'); });
            var m = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
            return m.find() ? Double.parseDouble(m.group(1)) : Double.MAX_VALUE;
        });
        assertThat(plan.toString()).as(plan.toString()).contains("Index").doesNotContain("Seq Scan");
        assertThat(ms).as(plan.toString()).isLessThanOrEqualTo(5.0);
    }
}
