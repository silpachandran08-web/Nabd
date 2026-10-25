package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-067: same-tenant contention on the chain head, nightly verify metric, monthly signed seals. */
class Ops067AuditChainSealApiTest extends ApiTestBase {

    private static final String INSERT = "INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type) "
            + "VALUES (?, 'system', 'System', 'System', 'ops067.write', 'ops067')";
    private static final String KEY = "test-only-" + UUID.randomUUID();

    @Autowired AuditService auditService;
    @Autowired TenantScope tenantScope;
    @Autowired MeterRegistry meters;

    private AuditChainSealer sealer(String key) {
        return new AuditChainSealer(jdbc, tenantScope, meters, key);
    }

    private void record(UUID t) {
        inTenantTx(t, () -> auditService.record(t, "system", null, "System", "System", null, "ops067.write", "ops067", UUID.randomUUID(), null, Map.of("k", "v")));
    }

    /** Database time of one audit insert including the chain-head wait (EXPLAIN ANALYZE runs the trigger), in ms. */
    private static double insertMs(Connection c, UUID t) throws Exception {
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
    void twentyConcurrentWritersOfOneTenant_p99ChainHeadWaitUnder50ms_noFork() {
        // Timing is judged only on a quiet box (21 engineers share 2 CPUs: a loaded one measures the scheduler); -Dops067.force=true
        // judges it anyway. The staging k6 contention profile is the real measurement. Correctness below always runs.
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        boolean judge = Boolean.getBoolean("ops067.force") || load < Runtime.getRuntime().availableProcessors() * 0.75;
        UUID t = seedTenant().id();
        record(t);
        List<Callable<List<Double>>> work = new ArrayList<>();
        for (int w = 0; w < 20; w++) work.add(() -> {
            List<Double> ms = new ArrayList<>();
            try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection(); Statement s = c.createStatement()) {
                c.setAutoCommit(false);
                for (int i = 0; i < 13; i++) {
                    s.execute("SELECT set_config('app.tenant_id', '" + t + "', true)");
                    double m = insertMs(c, t);
                    if (i >= 3) ms.add(m); // first 3 per connection warm plans and caches
                    c.commit(); // the head lock is held only until here
                }
            }
            return ms;
        });
        double[] all = ConcurrencyHarness.race(work).stream().flatMap(List::stream).mapToDouble(Double::doubleValue).sorted().toArray();
        double p99 = all[(int) Math.ceil(all.length * 0.99) - 1];
        System.out.printf("OPS-067 same-tenant audit insert p99 = %.3f ms over %d inserts, 20 writers, load %.2f, judged=%b%n", p99, all.length, load, judge);
        assertThat(all).hasSize(200);
        if (judge) assertThat(p99).as("p99 insert incl. chain-head wait, ms").isLessThan(50.0);
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t))).isNull();
    }

    @Test
    void monthlySeal_isSigned_idempotent_tenantScoped_andNeedsAKey() {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        record(a);
        record(a);
        LocalDate month = new JdbcTemplate(superuserDataSource()).queryForObject("SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date", LocalDate.class);
        AuditChainSealer s = sealer(KEY);

        assertThat(s.sealMonth(month)).isGreaterThanOrEqualTo(1); // tenant a (b has no rows, others may exist in a shared DB)
        assertThat(s.sealMonth(month)).as("second run writes nothing").isZero();

        Map<String, Object> seal = inTenantTx(a, () -> jdbc.queryForMap("SELECT last_id, last_hash, signature FROM audit_chain_seals WHERE month = ?", month));
        Map<String, Object> head = new JdbcTemplate(superuserDataSource()).queryForMap("SELECT last_id, last_hash FROM audit_chain_heads WHERE tenant_id = ?", a);
        assertThat(seal.get("last_id")).isEqualTo(head.get("last_id"));
        assertThat(seal.get("last_hash")).isEqualTo(head.get("last_hash"));
        assertThat(seal.get("signature")).isEqualTo(s.sign(a, month, (Long) head.get("last_id"), (String) head.get("last_hash")));
        assertThat(inTenantTx(b, () -> jdbc.queryForObject("SELECT count(*) FROM audit_chain_seals WHERE tenant_id = ?", Integer.class, a))).as("cross-tenant").isZero();
        assertThatThrownBy(() -> sealer("").sealMonth(month)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inTenantTx(a, () -> jdbc.update("DELETE FROM audit_chain_seals"))).as("append-only for the app role").isNotNull();
    }

    @Test
    void nightlyVerify_countsABrokenChain() throws Exception {
        UUID t = seedTenant().id();
        record(t);
        record(t);
        double before = meters.counter("audit.chain.verify.failures").count();
        long first = new JdbcTemplate(superuserDataSource()).queryForObject("SELECT min(id) FROM audit_log WHERE tenant_id = ?", Long.class, t);
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE audit_log SET after = '{\"k\":\"changed\"}' WHERE id = " + first);
        }
        int broken = sealer(KEY).verifyAll();
        assertThat(broken).isGreaterThanOrEqualTo(1);
        assertThat(meters.counter("audit.chain.verify.failures").count() - before).isGreaterThanOrEqualTo(broken);
    }
}
