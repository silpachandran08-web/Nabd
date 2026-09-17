package com.nabd.hms.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Goes through claim_outbox_events (V44), a SECURITY DEFINER escape hatch from outbox_events'
 * normal per-tenant RLS — see that migration's header. markDone/markFailed/markDead run as
 * ordinary RLS-scoped statements; callers must set TenantContext to the event's own tenant first
 * (OutboxEventProcessor does this before calling any of them). */
@Repository
class OutboxRepository {

    private final JdbcTemplate jdbc;

    OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<OutboxEvent> claim(int limit, int leaseSeconds) {
        return jdbc.query("SELECT * FROM claim_outbox_events(?, ?)", mapper(), limit, leaseSeconds);
    }

    void markDone(long id) {
        jdbc.update("UPDATE outbox_events SET status = 'done' WHERE id = ?", id);
    }

    void markFailed(long id, String error, Instant nextAttemptAt) {
        jdbc.update("""
                UPDATE outbox_events SET status = 'pending', last_error = ?, next_attempt_at = ?
                WHERE id = ?
                """,
                error, Timestamp.from(nextAttemptAt), id);
    }

    void markDead(long id, String error) {
        jdbc.update("UPDATE outbox_events SET status = 'dead', last_error = ? WHERE id = ?", error, id);
    }

    private RowMapper<OutboxEvent> mapper() {
        return (rs, i) -> new OutboxEvent(
                rs.getLong("id"),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getInt("attempts"));
    }
}
