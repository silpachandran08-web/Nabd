package com.nabd.hms.reports;

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

import static com.nabd.hms.reports.ReportsModels.ActorInfo;
import static com.nabd.hms.reports.ReportsModels.NoShowRiskRow;
import static com.nabd.hms.reports.ReportsModels.SourceCount;
import static com.nabd.hms.reports.ReportsModels.StaffCollectionRow;

@Repository
class ReportsRepository {

    private final JdbcTemplate jdbc;

    ReportsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // NB-229: scoped to a live query, not NB-228's separate read model (doesn't exist).
    // Explicit UTC instant range rather than "created_at::date = ?" — that cast resolves against
    // Postgres's SESSION timezone, not necessarily UTC, and silently drifted "today" by a day.
    BigDecimal billedOn(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return nz(jdbc.queryForObject("SELECT COALESCE(SUM(total), 0) FROM invoices " +
                        "WHERE tenant_id = ? AND created_at >= ? AND created_at < ?",
                BigDecimal.class, tenantId, Timestamp.from(dayStart), Timestamp.from(dayEnd)));
    }

    BigDecimal collectedOn(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return nz(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM invoice_payments " +
                        "WHERE tenant_id = ? AND recorded_at >= ? AND recorded_at < ?",
                BigDecimal.class, tenantId, Timestamp.from(dayStart), Timestamp.from(dayEnd)));
    }

    BigDecimal outstandingTotal(UUID tenantId) {
        return nz(jdbc.queryForObject("SELECT COALESCE(SUM(total - paid), 0) FROM invoices " +
                "WHERE tenant_id = ? AND status IN ('unpaid', 'partial')", BigDecimal.class, tenantId));
    }

    int invoiceCountOn(UUID tenantId, Instant dayStart, Instant dayEnd) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM invoices WHERE tenant_id = ? AND created_at >= ? AND created_at < ?",
                Integer.class, tenantId, Timestamp.from(dayStart), Timestamp.from(dayEnd));
        return n == null ? 0 : n;
    }

    int paymentCountOn(UUID tenantId, Instant dayStart, Instant dayEnd) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM invoice_payments WHERE tenant_id = ? AND recorded_at >= ? AND recorded_at < ?",
                Integer.class, tenantId, Timestamp.from(dayStart), Timestamp.from(dayEnd));
        return n == null ? 0 : n;
    }

    // NB-230/NB-079: single-axis source, straight off queue_entries.source.
    List<SourceCount> sourceBreakdown(UUID tenantId, LocalDate since) {
        return jdbc.query("SELECT source, COUNT(*) AS visit_count FROM queue_entries " +
                        "WHERE tenant_id = ? AND queue_date >= ? GROUP BY source ORDER BY visit_count DESC",
                (rs, i) -> new SourceCount(rs.getString("source"), rs.getLong("visit_count")),
                tenantId, Date.valueOf(since));
    }

    // NB-231: scopedToStaffId null = every staff member (Owner/manager view); non-null = that
    // staff member's own row only (NB-051's exact row-scoping convention, reused here).
    List<StaffCollectionRow> staffCollection(UUID tenantId, Instant since, UUID scopedToStaffId) {
        return jdbc.query("SELECT ip.recorded_by, s.name, SUM(ip.amount) AS collected, COUNT(*) AS payment_count " +
                        "FROM invoice_payments ip JOIN staff s ON s.id = ip.recorded_by " +
                        "WHERE ip.tenant_id = ? AND ip.recorded_at >= ? " +
                        "AND (?::uuid IS NULL OR ip.recorded_by = ?::uuid) " +
                        "GROUP BY ip.recorded_by, s.name ORDER BY collected DESC",
                (rs, i) -> new StaffCollectionRow(UUID.fromString(rs.getString("recorded_by")), rs.getString("name"),
                        rs.getBigDecimal("collected"), rs.getLong("payment_count")),
                tenantId, Timestamp.from(since), scopedToStaffId, scopedToStaffId);
    }

    // NB-234: aggregate-only retention numbers — no per-patient row is ever returned from here.
    int totalActivePatients(UUID tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE tenant_id = ? AND status = 'active'",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    int repeatPatientCount(UUID tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT patient_id FROM queue_entries " +
                        "WHERE tenant_id = ? AND status = 'completed' GROUP BY patient_id HAVING COUNT(*) > 1) x",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    int completedVisitCount(UUID tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM queue_entries WHERE tenant_id = ? AND status = 'completed'",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    int patientsWithAtLeastOneVisit(UUID tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT patient_id) FROM queue_entries WHERE tenant_id = ? AND status = 'completed'",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    /** NB-235: the rule itself lives here, in one place, so ReportsService can echo its exact
     * wording back on the response — "stated on the surface" per the ticket's acceptance bar. */
    List<NoShowRiskRow> noShowRiskToday(UUID tenantId, LocalDate today, LocalDate since, int minPriorNoShows) {
        return jdbc.query("""
                SELECT q.patient_id, p.name, cnt.no_show_count
                FROM queue_entries q
                JOIN patients p ON p.id = q.patient_id
                JOIN (
                  SELECT patient_id, COUNT(*) AS no_show_count FROM queue_entries
                  WHERE tenant_id = ? AND status = 'no_show' AND queue_date >= ?
                  GROUP BY patient_id HAVING COUNT(*) >= ?
                ) cnt ON cnt.patient_id = q.patient_id
                WHERE q.tenant_id = ? AND q.queue_date = ? AND q.status NOT IN ('completed', 'no_show')
                ORDER BY cnt.no_show_count DESC
                """,
                (rs, i) -> new NoShowRiskRow(UUID.fromString(rs.getString("patient_id")), rs.getString("name"),
                        rs.getLong("no_show_count")),
                tenantId, Date.valueOf(since), minPriorNoShows, tenantId, Date.valueOf(today));
    }

    Optional<ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
