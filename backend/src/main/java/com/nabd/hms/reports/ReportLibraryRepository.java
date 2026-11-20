package com.nabd.hms.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-047: SQL of the reports library. Aggregates read tenant_daily_metrics / doctor_daily_metrics (OPS-010 rollups); drill-down
 * rows are keyset-paged OLTP reads, newest first, limit+1 rows so the caller can tell whether another page exists. Every row comes
 * back as a column-label map (timestamps as Instant, dates as LocalDate) which the service shapes per report.
 */
@Repository
class ReportLibraryRepository {

    private final JdbcTemplate jdbc;

    ReportLibraryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Object v = rs.getObject(i);
                if (v instanceof Timestamp t) v = t.toInstant();
                else if (v instanceof Date d) v = d.toLocalDate();
                m.put(md.getColumnLabel(i), v);
            }
            return m;
        }, args);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    // ── rollups ──

    List<Map<String, Object>> dayRollups(UUID tenantId, LocalDate from, LocalDate to) {
        return rows("SELECT day, visits, completed, no_shows, billed, collected, tax, updated_at FROM tenant_daily_metrics " +
                "WHERE tenant_id = ? AND day >= ? AND day <= ? ORDER BY day", tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    /** onlyDoctor null = every doctor (full team view). */
    List<Map<String, Object>> doctorRollups(UUID tenantId, LocalDate from, LocalDate to, UUID onlyDoctor) {
        return rows("""
                SELECT d.doctor_id AS id, s.name AS doctor, SUM(d.visits) AS visits, SUM(d.completed) AS completed, SUM(d.no_shows) AS no_shows,
                       SUM(d.billed) AS billed, SUM(d.collected) AS collected, max(d.updated_at) AS updated_at
                FROM doctor_daily_metrics d JOIN staff s ON s.id = d.doctor_id
                WHERE d.tenant_id = ? AND d.day >= ? AND d.day <= ? AND (?::uuid IS NULL OR d.doctor_id = ?::uuid)
                GROUP BY d.doctor_id, s.name ORDER BY SUM(d.collected) DESC, s.name
                """, tenantId, Date.valueOf(from), Date.valueOf(to), onlyDoctor, onlyDoctor);
    }

    // ── drill-down pages ──

    List<Map<String, Object>> payments(UUID tenantId, Instant start, Instant end, Instant afterAt, UUID afterId, int limit) {
        return rows("""
                SELECT p.id, p.recorded_at, i.invoice_number AS invoice, pt.name AS patient, p.method, p.amount, s.name AS recorded_by
                FROM invoice_payments p JOIN invoices i ON i.id = p.invoice_id JOIN patients pt ON pt.id = i.patient_id JOIN staff s ON s.id = p.recorded_by
                WHERE p.tenant_id = ? AND p.recorded_at >= ? AND p.recorded_at < ?
                  AND (?::timestamptz IS NULL OR (p.recorded_at, p.id) < (?::timestamptz, ?::uuid))
                ORDER BY p.recorded_at DESC, p.id DESC LIMIT ?
                """, tenantId, ts(start), ts(end), ts(afterAt), ts(afterAt), afterId, limit);
    }

    List<Map<String, Object>> taxInvoices(UUID tenantId, Instant start, Instant end, Instant afterAt, UUID afterId, int limit) {
        return rows("""
                SELECT i.id, i.created_at, i.invoice_number AS invoice, i.subtotal, i.discount, i.tax, i.total, i.status
                FROM invoices i
                WHERE i.tenant_id = ? AND i.status <> 'void' AND i.tax > 0 AND i.created_at >= ? AND i.created_at < ?
                  AND (?::timestamptz IS NULL OR (i.created_at, i.id) < (?::timestamptz, ?::uuid))
                ORDER BY i.created_at DESC, i.id DESC LIMIT ?
                """, tenantId, ts(start), ts(end), ts(afterAt), ts(afterAt), afterId, limit);
    }

    long taxInvoiceCount(UUID tenantId, Instant start, Instant end) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ? AND status <> 'void' AND tax > 0 AND created_at >= ? AND created_at < ?",
                Long.class, tenantId, ts(start), ts(end));
        return n == null ? 0 : n;
    }

    List<Map<String, Object>> visits(UUID tenantId, LocalDate from, LocalDate to, LocalDate afterDay, UUID afterId, int limit) {
        return rows("""
                SELECT q.id, q.queue_date AS day, q.token_number AS token, q.source, pt.name AS patient, q.status
                FROM queue_entries q JOIN patients pt ON pt.id = q.patient_id
                WHERE q.tenant_id = ? AND q.queue_date >= ? AND q.queue_date <= ?
                  AND (?::date IS NULL OR (q.queue_date, q.id) < (?::date, ?::uuid))
                ORDER BY q.queue_date DESC, q.id DESC LIMIT ?
                """, tenantId, Date.valueOf(from), Date.valueOf(to), afterDay == null ? null : Date.valueOf(afterDay),
                afterDay == null ? null : Date.valueOf(afterDay), afterId, limit);
    }

    /** OWN-09: a completed, billed=true procedure whose charge never reached that visit's invoice, amount >= threshold, completed in the range. */
    private static final String LEAKAGE_WHERE = """
            po.tenant_id = ? AND po.status = 'completed' AND po.billed = true AND po.completed_at >= ? AND po.completed_at < ? AND po.base_amount >= ?
              AND NOT EXISTS (SELECT 1 FROM invoice_line_items ili JOIN invoices inv ON inv.id = ili.invoice_id
                              WHERE inv.tenant_id = po.tenant_id AND inv.queue_entry_id = po.queue_entry_id AND ili.charge_code = po.charge_code)
            """;

    List<Map<String, Object>> leakage(UUID tenantId, Instant start, Instant end, BigDecimal min, Instant afterAt, UUID afterId, int limit) {
        return rows("SELECT po.id, po.completed_at, pt.name AS patient, po.charge_code AS code, po.charge_name AS charge, po.base_amount AS amount " +
                "FROM procedure_orders po JOIN patients pt ON pt.id = po.patient_id WHERE " + LEAKAGE_WHERE +
                "AND (?::timestamptz IS NULL OR (po.completed_at, po.id) < (?::timestamptz, ?::uuid)) ORDER BY po.completed_at DESC, po.id DESC LIMIT ?",
                tenantId, ts(start), ts(end), min, ts(afterAt), ts(afterAt), afterId, limit);
    }

    /** One row per clinic day: count and value of leaked procedures (for the totals and the chart). */
    List<Map<String, Object>> leakageByDay(UUID tenantId, Instant start, Instant end, BigDecimal min, ZoneId zone) {
        return rows("SELECT (po.completed_at AT TIME ZONE ?)::date AS day, count(*) AS n, SUM(po.base_amount) AS amount FROM procedure_orders po WHERE " +
                LEAKAGE_WHERE + "GROUP BY 1 ORDER BY 1", zone.getId(), tenantId, ts(start), ts(end), min);
    }

    List<Map<String, Object>> packages(UUID tenantId, int limit, int offset) {
        return rows("""
                SELECT pi.id, pt.name AS patient, pi.package_name AS package, pi.validity_end AS valid_to,
                       SUM(ii.quantity_total - ii.quantity_consumed) AS sessions_left,
                       ROUND(SUM((ii.quantity_total - ii.quantity_consumed) * ii.allocated_price / NULLIF(ii.quantity_total, 0)), 2) AS value_left
                FROM package_instances pi JOIN patients pt ON pt.id = pi.patient_id JOIN package_instance_items ii ON ii.instance_id = pi.id
                WHERE pi.tenant_id = ? AND pi.status = 'active'
                GROUP BY pi.id, pt.name, pi.package_name, pi.validity_end
                HAVING SUM(ii.quantity_total - ii.quantity_consumed) > 0
                ORDER BY pi.validity_end, pi.id LIMIT ? OFFSET ?
                """, tenantId, limit, offset);
    }

    Map<String, Object> packageTotals(UUID tenantId) {
        return rows("""
                SELECT count(*) AS packages, COALESCE(SUM(left_n), 0) AS sessions, COALESCE(SUM(value), 0) AS value FROM (
                  SELECT SUM(ii.quantity_total - ii.quantity_consumed) AS left_n,
                         SUM((ii.quantity_total - ii.quantity_consumed) * ii.allocated_price / NULLIF(ii.quantity_total, 0)) AS value
                  FROM package_instances pi JOIN package_instance_items ii ON ii.instance_id = pi.id
                  WHERE pi.tenant_id = ? AND pi.status = 'active' GROUP BY pi.id HAVING SUM(ii.quantity_total - ii.quantity_consumed) > 0) x
                """, tenantId).get(0);
    }

    /** Doctor-announced delays in [start, end); onlyDoctor null = every doctor. */
    List<Map<String, Object>> doctorDelays(UUID tenantId, Instant start, Instant end, ZoneId zone, UUID onlyDoctor) {
        return rows("""
                SELECT x.doctor_id AS id, s.name AS doctor, count(*) AS delays, avg(x.delay_minutes) AS avg_minutes,
                       count(DISTINCT x.day) FILTER (WHERE x.day_count > 1) AS repeat_days
                FROM (SELECT dd.doctor_id, dd.delay_minutes, (dd.announced_at AT TIME ZONE ?)::date AS day,
                             count(*) OVER (PARTITION BY dd.doctor_id, (dd.announced_at AT TIME ZONE ?)::date) AS day_count
                      FROM doctor_delays dd WHERE dd.tenant_id = ? AND dd.announced_at >= ? AND dd.announced_at < ?
                        AND (?::uuid IS NULL OR dd.doctor_id = ?::uuid)) x
                JOIN staff s ON s.id = x.doctor_id GROUP BY x.doctor_id, s.name ORDER BY count(*) DESC, s.name
                """, zone.getId(), zone.getId(), tenantId, ts(start), ts(end), onlyDoctor, onlyDoctor);
    }
}
