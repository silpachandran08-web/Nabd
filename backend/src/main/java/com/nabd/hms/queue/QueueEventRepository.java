package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** ACC-015: queue_entry_events. Always called inside the transaction of the change it records, so a rollback leaves no row. */
@Repository
class QueueEventRepository {

    private final JdbcTemplate jdbc;

    QueueEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Encounter, doctor and clinic date are read from the entry row itself (they never change on a transition). */
    void record(UUID tenantId, UUID entryId, String fromStatus, String toStatus, String kind, Integer minutes,
                Instant at, UUID actorId, String source) {
        jdbc.update("INSERT INTO queue_entry_events (tenant_id, queue_entry_id, encounter_id, doctor_id, clinic_date, " +
                        "from_status, to_status, kind, minutes, occurred_at, actor_id, source) " +
                        "SELECT tenant_id, id, encounter_id, doctor_id, queue_date, ?, ?, ?, ?, ?, ?, ? " +
                        "FROM queue_entries WHERE tenant_id = ? AND id = ?",
                fromStatus, toStatus, kind, minutes, Timestamp.from(at), actorId, source, tenantId, entryId);
    }
}
