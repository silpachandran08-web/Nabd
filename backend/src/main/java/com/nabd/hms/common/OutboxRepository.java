package com.nabd.hms.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Goes through claim_outbox_events (V44), a SECURITY DEFINER escape hatch from outbox_events'
 * normal per-tenant RLS — see that migration's header. markDone/markFailed/markDead run as
 * ordinary RLS-scoped statements; callers must set TenantContext to the event's own tenant first
 * (OutboxEventProcessor does this before calling any of them).
 *
 * <p>OPS-014 fencing: every write after the claim carries {@code status = 'processing' AND attempts = ?}
 * (the attempts value this worker claimed with). A reclaim bumps attempts, so a worker whose lease expired
 * updates 0 rows instead of finishing or resetting someone else's event. Each returns the rows updated. */
@Repository
class OutboxRepository {

    private static final String FENCE = " WHERE id = ? AND status = 'processing' AND attempts = ?";

    private final JdbcTemplate jdbc;

    OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<OutboxEvent> claim(int limit, int leaseSeconds) {
        return jdbc.query("SELECT * FROM claim_outbox_events(?, ?)", mapper(), limit, leaseSeconds);
    }

    /** Renews the lease of every event in {@code events} this worker still owns (fenced, cross-tenant via
     * extend_outbox_leases, V1320) and returns their ids. */
    Set<Long> extendLeases(List<OutboxEvent> events, int leaseSeconds) {
        Long[] ids = events.stream().map(OutboxEvent::id).toArray(Long[]::new);
        Integer[] attempts = events.stream().map(OutboxEvent::attempts).toArray(Integer[]::new);
        return new HashSet<>(jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT * FROM extend_outbox_leases(?, ?, ?)");
            ps.setArray(1, con.createArrayOf("bigint", ids));
            ps.setArray(2, con.createArrayOf("integer", attempts));
            ps.setInt(3, leaseSeconds);
            return ps;
        }, (rs, i) -> rs.getLong(1)));
    }

    int markDone(OutboxEvent e) {
        return jdbc.update("UPDATE outbox_events SET status = 'done'" + FENCE, e.id(), e.attempts());
    }

    int markFailed(OutboxEvent e, String error, Instant nextAttemptAt) {
        return jdbc.update("UPDATE outbox_events SET status = 'pending', last_error = ?, next_attempt_at = ?" + FENCE,
                error, Timestamp.from(nextAttemptAt), e.id(), e.attempts());
    }

    int markDead(OutboxEvent e, String error) {
        return jdbc.update("UPDATE outbox_events SET status = 'dead', last_error = ?" + FENCE, error, e.id(), e.attempts());
    }

    /** Cross-tenant, via purge_outbox_events (V1320). */
    int purgeBatch(int doneDays, int deadDays, int batch) {
        return jdbc.queryForObject("SELECT purge_outbox_events(?, ?, ?)", Integer.class, doneDays, deadDays, batch);
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
