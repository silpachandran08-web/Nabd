package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.DayCloseHistoryResponse;
import com.nabd.hms.billing.dto.DayCloseSummaryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Day-close figures for one clinic day ([dayStart, dayEnd) in the clinic's zone) and the
 * day_closes row itself (V48). Tenant-scoped — callers set TenantContext first. */
@Repository
class DayCloseRepository {

    record CloseRow(UUID id, String status, BigDecimal expectedCash, BigDecimal countedCash, BigDecimal variance,
                    String note, String closedByName, Instant closedAt, String reopenedByName, Instant reopenedAt,
                    String reopenReason, int closeCount) {
    }

    private final JdbcTemplate jdbc;

    DayCloseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    DayCloseSummaryResponse.Totals totals(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return jdbc.queryForObject("""
                SELECT (SELECT COALESCE(SUM(total), 0) FROM invoices WHERE tenant_id = ? AND status <> 'void' AND created_at >= ? AND created_at < ?) AS billed,
                       (SELECT count(*) FROM invoices WHERE tenant_id = ? AND status <> 'void' AND created_at >= ? AND created_at < ?) AS bills,
                       (SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE tenant_id = ? AND recorded_at >= ? AND recorded_at < ?) AS collected,
                       (SELECT count(*) FROM invoice_payments WHERE tenant_id = ? AND recorded_at >= ? AND recorded_at < ?) AS payments,
                       (SELECT COALESCE(SUM(tax), 0) FROM invoices WHERE tenant_id = ? AND status <> 'void' AND created_at >= ? AND created_at < ?) AS tax
                """, (rs, i) -> new DayCloseSummaryResponse.Totals(rs.getBigDecimal("billed"), rs.getInt("bills"),
                        rs.getBigDecimal("collected"), rs.getInt("payments"), rs.getBigDecimal("tax")),
                tenantId, ts(dayStart), ts(dayEnd), tenantId, ts(dayStart), ts(dayEnd), tenantId, ts(dayStart), ts(dayEnd),
                tenantId, ts(dayStart), ts(dayEnd), tenantId, ts(dayStart), ts(dayEnd));
    }

    /** Every method the schema allows, zero-filled, in a fixed order. */
    List<DayCloseSummaryResponse.Method> methods(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return jdbc.query("""
                SELECT m.method, COALESCE(SUM(p.amount), 0) AS amount, count(p.id) AS transactions
                FROM (VALUES ('cash', 1), ('card', 2), ('upi', 3), ('other', 4)) AS m(method, ord)
                LEFT JOIN invoice_payments p ON p.method = m.method AND p.tenant_id = ? AND p.recorded_at >= ? AND p.recorded_at < ?
                GROUP BY m.method, m.ord ORDER BY m.ord
                """, (rs, i) -> new DayCloseSummaryResponse.Method(rs.getString("method"), rs.getBigDecimal("amount"),
                        rs.getInt("transactions")), tenantId, ts(dayStart), ts(dayEnd));
    }

    /** Balances still owed on the day's own bills. */
    DayCloseSummaryResponse.Outstanding outstanding(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(total - paid), 0) AS amount, count(DISTINCT patient_id) AS patients, count(*) AS bills
                FROM invoices WHERE tenant_id = ? AND status IN ('unpaid', 'partial') AND created_at >= ? AND created_at < ?
                """, (rs, i) -> new DayCloseSummaryResponse.Outstanding(rs.getBigDecimal("amount"), rs.getInt("patients"),
                        rs.getInt("bills")), tenantId, ts(dayStart), ts(dayEnd));
    }

    /** Visits waiting at a billing stop — the reception checkout worklist. */
    int pendingCheckouts(UUID tenantId, LocalDate day) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE tenant_id = ? AND queue_date = ? " +
                "AND status IN ('checkout_pending', 'billing_pending')", Integer.class, tenantId, Date.valueOf(day));
        return n == null ? 0 : n;
    }

    /**
     * Consultations that ended (the doctor sent them to checkout) with no bill at all — these block
     * the close. A visit already marked completed without a bill can't be sent to checkout any more,
     * so it isn't counted: it would block the day forever with nothing anyone could do about it.
     */
    List<DayCloseSummaryResponse.Unbilled> unbilled(UUID tenantId, LocalDate day) {
        return jdbc.query("""
                SELECT q.id, q.token_number, p.name AS patient_name, s.name AS doctor_name, q.updated_at
                FROM queue_entries q
                JOIN patients p ON p.id = q.patient_id
                LEFT JOIN staff s ON s.id = q.doctor_id
                WHERE q.tenant_id = ? AND q.queue_date = ? AND q.status = 'checkout_pending'
                  AND NOT EXISTS (SELECT 1 FROM invoices i WHERE i.queue_entry_id = q.id)
                ORDER BY q.updated_at
                """, (rs, i) -> new DayCloseSummaryResponse.Unbilled(rs.getObject("id", UUID.class), rs.getInt("token_number"),
                        rs.getString("patient_name"), rs.getString("doctor_name"), rs.getTimestamp("updated_at").toInstant()),
                tenantId, Date.valueOf(day));
    }

    /** Same per-line rounding as CheckoutService.computeTotals, so the tax column adds up to the bills' tax. */
    List<DayCloseSummaryResponse.TaxBand> taxBands(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return jdbc.query("""
                SELECT li.tax_rate_percent AS rate, SUM(li.line_total) AS taxable,
                       SUM(ROUND(li.line_total * li.tax_rate_percent / 100, 2)) AS tax
                FROM invoice_line_items li JOIN invoices i ON i.id = li.invoice_id
                WHERE i.tenant_id = ? AND i.created_at >= ? AND i.created_at < ?
                GROUP BY li.tax_rate_percent ORDER BY li.tax_rate_percent
                """, (rs, i) -> new DayCloseSummaryResponse.TaxBand(rs.getBigDecimal("rate"), rs.getBigDecimal("taxable"),
                        rs.getBigDecimal("tax")), tenantId, ts(dayStart), ts(dayEnd));
    }

    /** Bills paid with more than one method today (each tender is reconciled separately). */
    int splitPaymentBills(UUID tenantId, Instant dayStart, Instant dayEnd) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT invoice_id FROM invoice_payments
                                      WHERE tenant_id = ? AND recorded_at >= ? AND recorded_at < ?
                                      GROUP BY invoice_id HAVING count(DISTINCT method) > 1) x
                """, Integer.class, tenantId, ts(dayStart), ts(dayEnd));
        return n == null ? 0 : n;
    }

    record Voided(int count, BigDecimal total) {
    }

    /** OWN-019: invoices voided through the Sprint 3 void flow within the day, by voided_at. */
    Voided voided(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, COALESCE(SUM(total), 0) AS amount FROM invoices
                WHERE tenant_id = ? AND status = 'void' AND voided_at >= ? AND voided_at < ?
                """, (rs, i) -> new Voided(rs.getInt("n"), rs.getBigDecimal("amount")), tenantId, ts(dayStart), ts(dayEnd));
    }

    Optional<CloseRow> findClose(UUID tenantId, LocalDate day) {
        return jdbc.query("""
                SELECT c.id, c.status, c.expected_cash, c.counted_cash, c.variance, c.note, cb.name AS closed_by_name,
                       c.closed_at, rb.name AS reopened_by_name, c.reopened_at, c.reopen_reason, c.close_count
                FROM day_closes c
                JOIN staff cb ON cb.id = c.closed_by
                LEFT JOIN staff rb ON rb.id = c.reopened_by
                WHERE c.tenant_id = ? AND c.close_date = ?
                """, (rs, i) -> new CloseRow(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getBigDecimal("expected_cash"), rs.getBigDecimal("counted_cash"), rs.getBigDecimal("variance"),
                        rs.getString("note"), rs.getString("closed_by_name"), rs.getTimestamp("closed_at").toInstant(),
                        rs.getString("reopened_by_name"), instant(rs.getTimestamp("reopened_at")), rs.getString("reopen_reason"),
                        rs.getInt("close_count")),
                tenantId, Date.valueOf(day)).stream().findFirst();
    }

    boolean isClosed(UUID tenantId, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM day_closes WHERE tenant_id = ? AND close_date = ? AND status = 'closed')",
                Boolean.class, tenantId, Date.valueOf(day)));
    }

    record Closed(UUID id, int closeCount) {
    }

    /** First close inserts; closing again after a reopen overwrites the counts, clears the reopen (it is kept in
     * day_close_events) and bumps close_count. */
    Closed upsertClose(UUID tenantId, LocalDate day, BigDecimal expected, BigDecimal counted, BigDecimal variance,
                       String note, UUID staffId) {
        return jdbc.queryForObject("""
                INSERT INTO day_closes (tenant_id, close_date, status, expected_cash, counted_cash, variance, note, closed_by)
                VALUES (?, ?, 'closed', ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, close_date) DO UPDATE SET
                  status = 'closed', expected_cash = EXCLUDED.expected_cash, counted_cash = EXCLUDED.counted_cash,
                  variance = EXCLUDED.variance, note = EXCLUDED.note, closed_by = EXCLUDED.closed_by, closed_at = now(),
                  reopened_by = NULL, reopened_at = NULL, reopen_reason = NULL, close_count = day_closes.close_count + 1
                RETURNING id, close_count
                """, (rs, i) -> new Closed(rs.getObject("id", UUID.class), rs.getInt("close_count")),
                tenantId, Date.valueOf(day), expected, counted, variance, note, staffId);
    }

    /** FIN-005: serialises close, reopen and every billing write of one tenant-day (two-key transaction lock, released
     * at commit). Close takes it before computing expected cash, so a payment either commits first and is counted, or
     * waits and then sees the day closed. */
    void lockDay(UUID tenantId, LocalDate day) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?), ?)", Object.class, "day-close:" + tenantId, (int) day.toEpochDay());
    }

    void insertEvent(UUID tenantId, UUID closeId, String action, BigDecimal expected, BigDecimal counted, BigDecimal variance,
                     String note, UUID actorId) {
        jdbc.update("INSERT INTO day_close_events (tenant_id, day_close_id, action, expected_cash, counted_cash, variance, note, actor_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", tenantId, closeId, action, expected, counted, variance, note, actorId);
    }

    /** One history row; the actor's name is resolved through iam's ActorDirectory (billing does not read staff). */
    record HistoryRow(UUID id, LocalDate date, String action, java.math.BigDecimal expectedCash, java.math.BigDecimal countedCash,
                      java.math.BigDecimal variance, String note, UUID actorId, Instant at) {
        DayCloseHistoryResponse.Item withActorName(String actorName) {
            return new DayCloseHistoryResponse.Item(id, date, action, expectedCash, countedCash, variance, note, actorName, at);
        }
    }

    /** Newest first; (beforeAt, beforeId) is the keyset cursor of the previous page's last item, both null for page one. */
    List<HistoryRow> history(UUID tenantId, LocalDate from, LocalDate to, Instant beforeAt, UUID beforeId, int limit) {
        return jdbc.query("""
                SELECT e.id, c.close_date, e.action, e.expected_cash, e.counted_cash, e.variance, e.note, e.actor_id, e.created_at
                FROM day_close_events e
                JOIN day_closes c ON c.id = e.day_close_id
                WHERE e.tenant_id = ? AND c.close_date >= ? AND c.close_date <= ?
                  AND (?::timestamptz IS NULL OR (e.created_at, e.id) < (?::timestamptz, ?::uuid))
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT ?
                """, (rs, i) -> new HistoryRow(rs.getObject("id", UUID.class), rs.getDate("close_date").toLocalDate(),
                        rs.getString("action"), rs.getBigDecimal("expected_cash"), rs.getBigDecimal("counted_cash"),
                        rs.getBigDecimal("variance"), rs.getString("note"), rs.getObject("actor_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, Date.valueOf(from), Date.valueOf(to), tsOrNull(beforeAt), tsOrNull(beforeAt), beforeId, limit);
    }

    void reopen(UUID tenantId, UUID closeId, UUID staffId, String reason) {
        jdbc.update("UPDATE day_closes SET status = 'reopened', reopened_by = ?, reopened_at = now(), reopen_reason = ? " +
                "WHERE tenant_id = ? AND id = ?", staffId, reason, tenantId, closeId);
    }

    Optional<String> region(UUID tenantId) {
        return jdbc.query("SELECT region_code FROM tenants WHERE id = ?", (rs, i) -> rs.getString(1), tenantId).stream().findFirst();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static Timestamp tsOrNull(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
