package com.nabd.hms.platform.activation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADM-005: master.activation_steps is under forced tenant RLS (V3120). Writes run in the clinic's tenant transaction; the
 * cross-clinic reads go through the V3120 definers. tenants (owned by platform, no RLS) gives cohorts and time to go-live. */
@Repository
class ActivationRepository {

    record Applied(int attempts, UUID ticketId) {
    }

    record FunnelRow(LocalDate week, String step, long reached, long completed) {
    }

    record Cohort(LocalDate week, long signups, Double medianHoursToLive) {
    }

    record Stuck(UUID tenantId, String step, Instant since, int nudgesSent) {
    }

    record Claim(UUID tenantId, String step, Instant previousNudgedAt) {
    }

    private final JdbcTemplate jdbc;

    ActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent upsert of one step event; empty when a newer (or the same) event was already applied. Tenant transaction. */
    Optional<Applied> apply(UUID tenantId, String step, String status, String errorCode, long eventId) {
        return jdbc.query("""
                INSERT INTO master.activation_steps AS a (tenant_id, step, status, completed_at, attempts, last_error_code, last_event_id)
                VALUES (?, ?, ?, CASE WHEN ? IN ('done','skipped') THEN now() END, CASE WHEN ? = 'failed' THEN 1 ELSE 0 END, ?, ?)
                ON CONFLICT (tenant_id, step) DO UPDATE SET
                  status = CASE WHEN EXCLUDED.status = 'reached' THEN a.status ELSE EXCLUDED.status END,
                  completed_at = CASE WHEN EXCLUDED.status IN ('done','skipped') THEN COALESCE(a.completed_at, now()) ELSE a.completed_at END,
                  attempts = a.attempts + EXCLUDED.attempts,
                  last_error_code = COALESCE(EXCLUDED.last_error_code, a.last_error_code),
                  last_event_id = EXCLUDED.last_event_id, updated_at = now()
                WHERE a.last_event_id < EXCLUDED.last_event_id
                RETURNING attempts, ticket_id""",
                (rs, i) -> new Applied(rs.getInt(1), rs.getObject(2, UUID.class)),
                tenantId, step, status, status, status, errorCode, eventId).stream().findFirst();
    }

    void setTicket(UUID tenantId, String step, UUID ticketId) {
        jdbc.update("UPDATE master.activation_steps SET ticket_id = ? WHERE tenant_id = ? AND step = ?", ticketId, tenantId, step);
    }

    List<FunnelRow> funnel(Instant from, Instant to, String market, String specialty) {
        return jdbc.query("SELECT * FROM activation_funnel(?, ?, ?, ?)",
                (rs, i) -> new FunnelRow(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                Timestamp.from(from), Timestamp.from(to), market, specialty);
    }

    /** Sign-ups per week and the median hours from sign-up to go-live of the clinics of that week that are live. */
    List<Cohort> cohorts(Instant from, Instant to, String market, String specialty) {
        return jdbc.query("""
                SELECT date_trunc('week', created_at)::date, count(*),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM setup_completed_at - created_at) / 3600)
                FROM tenants WHERE created_at >= ? AND created_at < ?
                  AND (?::text IS NULL OR region_code = ?) AND (?::text IS NULL OR ? = ANY (specialties))
                GROUP BY 1 ORDER BY 1""",
                (rs, i) -> new Cohort(rs.getObject(1, LocalDate.class), rs.getLong(2), (Double) rs.getObject(3)),
                Timestamp.from(from), Timestamp.from(to), market, market, specialty, specialty);
    }

    List<Stuck> stuck(Instant before, Instant afterSince, UUID afterTenant, String afterStep, int limit) {
        return jdbc.query("SELECT * FROM activation_stuck(?, ?, ?, ?, ?)",
                (rs, i) -> new Stuck(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4)),
                Timestamp.from(before), afterSince == null ? null : Timestamp.from(afterSince), afterTenant, afterStep, limit);
    }

    /** Claims due nudges across clinics (outside any tenant scope, through the definer). */
    List<Claim> claimNudges(Instant stuckBefore, Instant renudgeBefore, int limit) {
        return jdbc.query("SELECT * FROM claim_activation_nudges(?, ?, ?)",
                (rs, i) -> new Claim(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()),
                Timestamp.from(stuckBefore), Timestamp.from(renudgeBefore), limit);
    }

    /** The nudge count after the claim, read in the clinic's tenant transaction (part of the notification's dedupe key). */
    int nudgesSent(UUID tenantId, String step) {
        Integer n = jdbc.queryForObject("SELECT nudges_sent FROM master.activation_steps WHERE tenant_id = ? AND step = ?", Integer.class, tenantId, step);
        return n == null ? 0 : n;
    }

    /** A claimed nudge that was not sent goes back (the next run retries it). Tenant transaction. */
    void putBack(UUID tenantId, String step, Instant previousNudgedAt) {
        jdbc.update("UPDATE master.activation_steps SET last_nudged_at = ?, nudges_sent = greatest(nudges_sent - 1, 0), updated_at = now() "
                + "WHERE tenant_id = ? AND step = ?", previousNudgedAt == null ? null : Timestamp.from(previousNudgedAt), tenantId, step);
    }
}
