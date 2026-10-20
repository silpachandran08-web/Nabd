package com.nabd.hms.patient;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OPS-004 / DP-13: one-off repair of queue_entries.updated_at rows that the V37/V43 backfills stamped with the
 * migration time. Restores updated_at = created_at for rows whose updated_at is exactly one of the given stamps.
 * Touches no other column. Batches of 10k, each in its own transaction with nabd.skip_updated_at (V225) so the
 * trigger does not re-stamp. One audit row per tenant run. Re-runnable: finished rows no longer match.
 * Enable with app.maintenance.last-visit-repair.stamps=<ISO instant>[,...] (the migration times, from flyway_schema_history).
 */
@Component
@ConditionalOnProperty("app.maintenance.last-visit-repair.stamps")
public class LastVisitRepairJob implements ApplicationRunner {

    static final int BATCH = 10_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final List<Instant> stamps;

    LastVisitRepairJob(JdbcTemplate jdbc, TransactionTemplate tx, TenantContext tenantContext, AuditService audit,
                       @org.springframework.beans.factory.annotation.Value("${app.maintenance.last-visit-repair.stamps}") String stamps) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.stamps = Arrays.stream(stamps.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Instant::parse).toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            run(tenantId, stamps);
        }
    }

    /** @return rows restored. */
    public int run(UUID tenantId, List<Instant> stamps) {
        if (stamps.isEmpty()) throw new IllegalArgumentException("at least one stamp is required");
        Timestamp[] ts = stamps.stream().map(Timestamp::from).toArray(Timestamp[]::new);
        int rows = 0, batches = 0, n;
        do {
            n = tx.execute(s -> {
                tenantContext.set(tenantId);
                jdbc.execute("SET LOCAL nabd.skip_updated_at = 'on'");
                return jdbc.update("""
                        UPDATE queue_entries SET updated_at = created_at WHERE id IN (
                          SELECT id FROM queue_entries WHERE tenant_id = ? AND updated_at = ANY (?) AND updated_at <> created_at
                          LIMIT ? FOR UPDATE SKIP LOCKED)""", ps -> {
                    ps.setObject(1, tenantId);
                    ps.setArray(2, ps.getConnection().createArrayOf("timestamptz", ts));
                    ps.setInt(3, BATCH);
                });
            });
            rows += n;
            if (n > 0) batches++;
        } while (n == BATCH);
        int total = rows, b = batches;
        tx.executeWithoutResult(s -> audit.record(tenantId, "system", null, "System", "System", null,
                "maintenance.last_visit_repair", "queue_entries", null, null,
                Map.of("rowsRestored", total, "batches", b, "stampCount", stamps.size(), "column", "updated_at")));
        return rows;
    }
}
