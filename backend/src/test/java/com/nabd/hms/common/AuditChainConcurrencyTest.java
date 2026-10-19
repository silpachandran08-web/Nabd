package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-038: per-tenant audit_chain_heads (V1380) — no fork, no cross-tenant waits, UTC hashing, v1 rows still verify. */
class AuditChainConcurrencyTest extends ApiTestBase {

    private static final String INSERT = "INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type) "
            + "VALUES (?, 'system', 'System', 'System', 'plt038.write', 'plt038')";

    @Autowired
    AuditService auditService;

    private Long verify(UUID t) {
        return inTenantTx(t, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t));
    }

    private void record(UUID t, String action) {
        inTenantTx(t, () -> auditService.record(t, "system", null, "System", "System", null, action, "plt038", UUID.randomUUID(), null, Map.of("k", "v")));
    }

    /** nabd_app connection inside an open transaction scoped to tenant t (the caller commits or rolls back). */
    private static Connection appTx(UUID t) throws SQLException {
        Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + t + "', true)");
        }
        return c;
    }

    private static long timedInsert(Connection c, UUID t) throws SQLException {
        long t0 = System.nanoTime();
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, t);
            ps.executeUpdate();
        }
        return System.nanoTime() - t0;
    }

    /** Server-side time of one audit insert, trigger included (EXPLAIN ANALYZE executes it), in ms. */
    private static double serverInsertMs(Connection c, UUID t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN (ANALYZE, TIMING OFF) " + INSERT)) {
            ps.setObject(1, t);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line.startsWith("Execution Time:")) return Double.parseDouble(line.replaceAll("[^0-9.]", ""));
                }
            }
        }
        throw new IllegalStateException("no Execution Time line");
    }

    @Test
    void fiftyParallelWritesForOneTenant_chainIntact_headPointsAtTheLastRow() {
        UUID t = seedTenant().id();
        ConcurrencyHarness.raceSame(50, () -> { record(t, "plt038.fifty"); return true; });

        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'plt038.fifty'", Integer.class))).isEqualTo(50);
        assertThat(verify(t)).isNull();
        Map<String, Object> head = new JdbcTemplate(superuserDataSource()).queryForMap(
                "SELECT h.last_id, h.last_hash, (SELECT max(id) FROM audit_log WHERE tenant_id = h.tenant_id) AS max_id, "
                        + "(SELECT row_hash FROM audit_log WHERE tenant_id = h.tenant_id ORDER BY id DESC LIMIT 1) AS max_hash "
                        + "FROM audit_chain_heads h WHERE h.tenant_id = ?", t);
        assertThat(head.get("last_id")).isEqualTo(head.get("max_id"));
        assertThat(head.get("last_hash")).isEqualTo(head.get("max_hash"));
    }

    @Test
    void tenTenantsInParallel_doNotWaitOnEachOther_onlyTheSameTenantWaits() throws Exception {
        List<UUID> tenants = new ArrayList<>();
        for (int i = 0; i < 10; i++) tenants.add(seedTenant().id());
        for (UUID t : tenants) record(t, "plt038.seed"); // every head exists before timing

        // Tenant 0 holds its head lock (uncommitted insert). The other nine still write at once; a second writer
        // for tenant 0 waits (proves the lock is real and per tenant).
        try (Connection holder = appTx(tenants.get(0))) {
            timedInsert(holder, tenants.get(0));
            List<Callable<Long>> others = new ArrayList<>();
            for (UUID t : tenants.subList(1, 10)) others.add(() -> {
                try (Connection c = appTx(t); Statement s = c.createStatement()) {
                    s.execute("SET LOCAL lock_timeout = '1s'");
                    long ns = timedInsert(c, t);
                    c.commit();
                    return ns;
                }
            });
            assertThat(ConcurrencyHarness.race(others)).hasSize(9).allMatch(ns -> ns < 1_000_000_000L);
            try (Connection same = appTx(tenants.get(0)); Statement s = same.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '300ms'");
                assertThatThrownBy(() -> timedInsert(same, tenants.get(0))).hasMessageContaining("lock timeout");
            }
            holder.rollback();
        }

        // p95 of 200 inserts: 10 tenants writing in parallel, 20 each, committed one by one. Asserted on the database
        // time (trigger included); the JDBC round trip on this shared test box is printed for information.
        List<Callable<List<double[]>>> work = new ArrayList<>();
        for (UUID t : tenants) work.add(() -> {
            List<double[]> ms = new ArrayList<>();
            try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection(); Statement s = c.createStatement()) {
                c.setAutoCommit(false);
                for (int i = 0; i < 20; i++) {
                    s.execute("SELECT set_config('app.tenant_id', '" + t + "', true)");
                    long t0 = System.nanoTime();
                    double server = serverInsertMs(c, t);
                    ms.add(new double[]{server, (System.nanoTime() - t0) / 1e6});
                    c.commit();
                }
            }
            return ms;
        });
        List<double[]> all = ConcurrencyHarness.race(work).stream().flatMap(List::stream).toList();
        int k = (int) Math.ceil(all.size() * 0.95) - 1;
        double p95ms = all.stream().mapToDouble(x -> x[0]).sorted().toArray()[k];
        double p95client = all.stream().mapToDouble(x -> x[1]).sorted().toArray()[k];
        System.out.printf("PLT-038 audit insert p95 = %.3f ms in the database, %.3f ms JDBC round trip, %d inserts, 10 tenants in parallel%n", p95ms, p95client, all.size());
        assertThat(all).hasSize(200);
        assertThat(p95ms).as("audit insert p95 ms (database time)").isLessThan(10.0);
        for (UUID t : tenants) assertThat(verify(t)).as("tenant " + t).isNull();
    }

    @Test
    void sessionTimeZoneDubai_rowHashEqualsTheUtcComputation_andVerifiesInAnyZone() throws Exception {
        UUID t = seedTenant().id();
        inTenantTx(t, () -> {
            jdbc.execute("SET LOCAL TimeZone = 'Asia/Dubai'");
            auditService.record(t, "system", null, "System", "System", "10.0.0.1", "plt038.tz", "plt038", UUID.randomUUID(), Map.of("a", 1), Map.of("b", 2));
        });
        try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
            s.execute("SET TimeZone = 'UTC'");
            try (ResultSet rs = s.executeQuery("SELECT hash_v, row_hash = audit_row_hash_v2(prev_hash, tenant_id, actor_type, actor_id, actor_name, actor_role, "
                    + "ip_address, action, entity_type, entity_id, before, after, created_at) AS same FROM audit_log WHERE tenant_id = '" + t + "' AND action = 'plt038.tz'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("hash_v")).isEqualTo(2);
                assertThat(rs.getBoolean("same")).as("hash under UTC equals hash written under Asia/Dubai").isTrue();
            }
        }
        for (String zone : List.of("UTC", "Asia/Dubai", "America/New_York")) {
            assertThat(inTenantTx(t, () -> {
                jdbc.execute("SET LOCAL TimeZone = '" + zone + "'");
                return jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t);
            })).as(zone).isNull();
        }
    }

    @Test
    void legacyV1RowsStillVerify_newRowsChainOntoThem_andTamperOfAV1RowIsCaught() throws Exception {
        UUID t = seedTenant().id();
        // Two rows exactly as V14/V126 wrote them (hash_v 1, created_at::text formula), with the trigger off.
        String v1 = "INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type, created_at, prev_hash, hash_v, row_hash) "
                + "SELECT s.t, 'system', 'x', 'System', 'legacy.v1', 'x', s.ts, s.p, 1, "
                + "audit_row_hash(s.p, s.t, 'system', NULL, 'x', 'System', NULL, 'legacy.v1', 'x', NULL, NULL, NULL, s.ts) "
                + "FROM (SELECT '" + t + "'::uuid AS t, now() AS ts, %s::text AS p) s RETURNING row_hash";
        long firstId;
        try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
            s.execute("SET session_replication_role = replica");
            String h1;
            try (ResultSet rs = s.executeQuery(String.format(v1, "NULL"))) { rs.next(); h1 = rs.getString(1); }
            s.executeQuery(String.format(v1, "'" + h1 + "'")).close();
            s.execute("DELETE FROM audit_chain_heads WHERE tenant_id = '" + t + "'"); // pre-migration chain: no head yet
        }
        record(t, "plt038.after-v1");
        record(t, "plt038.after-v1");

        List<Integer> versions = inTenantTx(t, () -> jdbc.queryForList("SELECT hash_v FROM audit_log WHERE tenant_id = ? ORDER BY id", Integer.class, t));
        assertThat(versions).containsExactly(1, 1, 2, 2);
        assertThat(verify(t)).isNull();

        firstId = inTenantTx(t, () -> jdbc.queryForObject("SELECT min(id) FROM audit_log WHERE tenant_id = ?", Long.class, t));
        try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.execute("UPDATE audit_log SET actor_name = 'TAMPERED' WHERE id = " + firstId);
        }
        assertThat(verify(t)).isEqualTo(firstId);
    }

    @Test
    void superuserEditOfAV2Row_isCaughtByTheNextVerify() throws Exception {
        UUID t = seedTenant().id();
        record(t, "plt038.a");
        record(t, "plt038.b");
        record(t, "plt038.c");
        long mid = inTenantTx(t, () -> jdbc.queryForObject("SELECT id FROM audit_log WHERE tenant_id = ? AND action = 'plt038.b'", Long.class, t));
        try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.execute("UPDATE audit_log SET after = '{\"k\":\"changed\"}' WHERE id = " + mid);
        }
        assertThat(verify(t)).isEqualTo(mid);
        assertThat(auditService.verify(t).intact()).isFalse();
    }

    @Test
    void appRoleCannotDisableTheTriggers_norTouchTheHeads_andOwnersAreAsSpecified() throws Exception {
        UUID t = seedTenant().id();
        record(t, "plt038.own");
        for (String sql : List.of(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_chain",
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update",
                "UPDATE audit_log SET action = 'x' WHERE tenant_id = '" + t + "'",
                "DELETE FROM audit_log WHERE tenant_id = '" + t + "'",
                "SELECT * FROM audit_chain_heads",
                "UPDATE audit_chain_heads SET last_hash = '' WHERE tenant_id = '" + t + "'")) {
            try (Connection c = appTx(t); Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.execute(sql)).as(sql).isInstanceOf(SQLException.class);
                c.rollback();
            }
        }
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        assertThat(su.queryForObject("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'audit_log'::regclass", String.class)).isEqualTo(MIGRATOR_ROLE);
        assertThat(su.queryForObject("SELECT pg_get_userbyid(proowner) || ':' || prosecdef FROM pg_proc WHERE proname = 'audit_log_chain'", String.class)).isEqualTo("nabd_definer:true");
        assertThat(verify(t)).isNull();
    }
}
