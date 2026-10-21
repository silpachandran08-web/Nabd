package com.nabd.hms.queue;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ACC-015: gives visits that predate queue_entry_events approximate history so the ETA has data from day one.
 * Per entry with no event at all: a check-in event at created_at and, for completed visits, an end event at the invoice
 * time (updated_at when there is no invoice). Rows are marked source='backfill'. Batches of BATCH entries, one transaction
 * each; re-runnable: an entry that has any event is skipped and a partial unique index backs that up.
 */
@Component
public class QueueEventBackfillJob {

    static final int BATCH = 2000;
    private static final Logger log = LoggerFactory.getLogger(QueueEventBackfillJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;

    QueueEventBackfillJob(JdbcTemplate jdbc, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
    }

    @Scheduled(cron = "${app.queue-events.backfill-cron:0 40 2 * * *}", zone = "${app.queue-events.backfill-zone:Asia/Kolkata}")
    @SchedulerLock(name = "queue-event-backfill", lockAtMostFor = "PT2H")
    public void scheduled() {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            log.info("queue event backfill: tenant {} wrote {} events", tenantId, run(tenantId));
        }
    }

    /** @return events written for this tenant. */
    public int run(UUID tenantId) {
        int total = 0, n;
        do {
            n = tenantScope.run(tenantId, () -> jdbc.queryForObject("""
                    WITH todo AS (
                      SELECT q.id, q.tenant_id, q.encounter_id, q.doctor_id, q.queue_date, q.status, q.created_at, q.updated_at
                      FROM queue_entries q
                      WHERE q.tenant_id = ? AND NOT EXISTS (SELECT 1 FROM queue_entry_events e WHERE e.queue_entry_id = q.id)
                      ORDER BY q.created_at LIMIT ?),
                    ins AS (
                      INSERT INTO queue_entry_events (tenant_id, queue_entry_id, encounter_id, doctor_id, clinic_date, from_status, to_status, kind, occurred_at, source)
                      SELECT tenant_id, id, encounter_id, doctor_id, queue_date, NULL, 'checked_in', 'transition', created_at, 'backfill' FROM todo
                      UNION ALL
                      SELECT t.tenant_id, t.id, t.encounter_id, t.doctor_id, t.queue_date, NULL, 'completed', 'transition',
                             GREATEST(t.created_at, COALESCE((SELECT i.created_at FROM invoices i WHERE i.queue_entry_id = t.id), t.updated_at)), 'backfill'
                      FROM todo t WHERE t.status = 'completed'
                      ON CONFLICT (queue_entry_id, kind, to_status) WHERE source = 'backfill' DO NOTHING
                      RETURNING 1)
                    SELECT count(*) FROM ins""", Integer.class, tenantId, BATCH));
            total += n;
        } while (n > 0);
        return total;
    }
}
