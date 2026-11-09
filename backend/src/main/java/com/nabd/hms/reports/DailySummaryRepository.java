package com.nabd.hms.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADM-043: settings, the figures of one clinic day (rollup + the live rows the rollup does not carry) and the close time inputs. */
@Repository
class DailySummaryRepository {

    record Settings(boolean enabled, boolean combined, List<UUID> recipients, String sendAt, LocalDate lastSentDay) {
        static final Settings DEFAULT = new Settings(false, false, List.of(), "close", null);
    }

    /** One clinic's figures for a day. by_method is the rollup's {method: amount} JSON; fresh = the rollup matches the live rows. */
    record Figures(UUID tenantId, String clinicName, String currency, int visits, int completed, int noShows, BigDecimal collected,
                   String byMethodJson, Integer avgWaitMinutes, int unbilled, BigDecimal unbilledAmount, boolean closed, boolean fresh) {
    }

    record Branch(UUID id, String name) {
    }

    private final JdbcTemplate jdbc;

    DailySummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Settings settings(UUID tenantId) {
        return jdbc.query("SELECT enabled, combined, recipients, send_at, last_sent_day FROM daily_summary_settings WHERE tenant_id = ?",
                (rs, i) -> new Settings(rs.getBoolean("enabled"), rs.getBoolean("combined"),
                        Arrays.asList((UUID[]) rs.getArray("recipients").getArray()), rs.getString("send_at"),
                        rs.getDate("last_sent_day") == null ? null : rs.getDate("last_sent_day").toLocalDate()), tenantId)
                .stream().findFirst().orElse(Settings.DEFAULT);
    }

    void upsertSettings(UUID tenantId, boolean enabled, boolean combined, List<UUID> recipients, String sendAt, UUID staffId) {
        jdbc.update("""
                INSERT INTO daily_summary_settings (tenant_id, enabled, combined, recipients, send_at, updated_by)
                VALUES (?, ?, ?, ?::uuid[], ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled, combined = EXCLUDED.combined, recipients = EXCLUDED.recipients,
                  send_at = EXCLUDED.send_at, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, tenantId, enabled, combined, "{" + String.join(",", recipients.stream().map(UUID::toString).toList()) + "}", sendAt, staffId);
    }

    void markSent(UUID tenantId, LocalDate day) {
        jdbc.update("UPDATE daily_summary_settings SET last_sent_day = ? WHERE tenant_id = ?", Date.valueOf(day), tenantId);
    }

    /** The clinics of this clinic's brand (itself alone when it has no brand), by name. tenants/brands carry no RLS (V6). */
    List<Branch> branches(UUID tenantId) {
        List<Branch> found = jdbc.query("""
                SELECT id, name FROM tenants WHERE id = ? OR (brand_id IS NOT NULL AND brand_id = (SELECT brand_id FROM tenants WHERE id = ?))
                ORDER BY name, id
                """, (rs, i) -> new Branch(rs.getObject("id", UUID.class), rs.getString("name")), tenantId, tenantId);
        return found;
    }

    Optional<Instant> closedAt(UUID tenantId, LocalDate day) {
        return jdbc.query("SELECT closed_at FROM day_closes WHERE tenant_id = ? AND close_date = ? AND status = 'closed'",
                (rs, i) -> rs.getTimestamp("closed_at").toInstant(), tenantId, Date.valueOf(day)).stream().findFirst();
    }

    /** Seconds after local midnight at which the last working-hours block of that weekday ends (a block ending after midnight counts as the next day); empty = no hours. */
    Optional<Integer> lastSessionEndSecond(UUID tenantId, int dayOfWeek0Sunday) {
        return Optional.ofNullable(jdbc.queryForObject("""
                SELECT max(CASE WHEN end_time < start_time THEN 86400 ELSE 0 END + extract(epoch FROM end_time))::int
                FROM doctor_working_hours WHERE tenant_id = ? AND day_of_week = ?
                """, Integer.class, tenantId, dayOfWeek0Sunday));
    }

    /**
     * The day's figures: counts and amounts from the OPS-010 rollup (zeros without a row), compared with the live rows for
     * freshness; average wait (check-in to in_consult) and the unbilled consultations (checkout_pending, no invoice) with the
     * value of their completed, billable procedures are live. start/end are the rollup's own day bounds (local midnight).
     */
    Figures figures(UUID tenantId, String clinicName, String currency, LocalDate day, Instant start, Instant end) {
        Date d = Date.valueOf(day);
        Timestamp s = Timestamp.from(start);
        Timestamp e = Timestamp.from(end);
        return jdbc.queryForObject("""
                WITH r AS (SELECT visits, completed, no_shows, collected, by_method::text AS by_method FROM tenant_daily_metrics WHERE tenant_id = ? AND day = ?),
                     live AS (SELECT count(*) AS visits, count(*) FILTER (WHERE status = 'completed') AS completed FROM queue_entries WHERE tenant_id = ? AND queue_date = ?),
                     paid AS (SELECT COALESCE(SUM(amount), 0) AS collected FROM invoice_payments WHERE tenant_id = ? AND recorded_at >= ? AND recorded_at < ?),
                     waits AS (SELECT avg(extract(epoch FROM (f.first_in - q.created_at)) / 60) AS minutes
                               FROM queue_entries q JOIN (SELECT queue_entry_id, min(occurred_at) AS first_in FROM queue_entry_events
                                                          WHERE tenant_id = ? AND clinic_date = ? AND to_status = 'in_consult' GROUP BY queue_entry_id) f ON f.queue_entry_id = q.id
                               WHERE q.tenant_id = ? AND q.queue_date = ?),
                     u AS (SELECT q.id FROM queue_entries q WHERE q.tenant_id = ? AND q.queue_date = ? AND q.status = 'checkout_pending'
                             AND NOT EXISTS (SELECT 1 FROM invoices i WHERE i.tenant_id = q.tenant_id AND i.queue_entry_id = q.id)),
                     unbilled AS (SELECT (SELECT count(*) FROM u) AS n,
                                         (SELECT COALESCE(SUM(po.base_amount), 0) FROM procedure_orders po JOIN u ON u.id = po.queue_entry_id
                                          WHERE po.tenant_id = ? AND po.status = 'completed' AND po.billed) AS amount)
                SELECT r.visits IS NOT NULL AS has_row, COALESCE(r.visits, 0) AS visits, COALESCE(r.completed, 0) AS completed, COALESCE(r.no_shows, 0) AS no_shows,
                       COALESCE(r.collected, 0) AS collected, COALESCE(r.by_method, '{}') AS by_method,
                       live.visits AS live_visits, live.completed AS live_completed, paid.collected AS live_collected,
                       waits.minutes AS wait_minutes, unbilled.n AS unbilled, unbilled.amount AS unbilled_amount,
                       EXISTS (SELECT 1 FROM day_closes c WHERE c.tenant_id = ? AND c.close_date = ? AND c.status = 'closed') AS closed
                FROM live, paid, waits, unbilled LEFT JOIN r ON true
                """, (rs, i) -> new Figures(tenantId, clinicName, currency, rs.getInt("visits"), rs.getInt("completed"), rs.getInt("no_shows"),
                        rs.getBigDecimal("collected"), rs.getString("by_method"),
                        rs.getObject("wait_minutes") == null ? null : (int) Math.round(rs.getDouble("wait_minutes")),
                        rs.getInt("unbilled"), rs.getBigDecimal("unbilled_amount"), rs.getBoolean("closed"),
                        rs.getBoolean("has_row") && rs.getInt("visits") == rs.getInt("live_visits") && rs.getInt("completed") == rs.getInt("live_completed")
                                && rs.getBigDecimal("collected").compareTo(rs.getBigDecimal("live_collected")) == 0),
                tenantId, d, tenantId, d, tenantId, s, e, tenantId, d, tenantId, d, tenantId, d, tenantId, tenantId, d);
    }
}
