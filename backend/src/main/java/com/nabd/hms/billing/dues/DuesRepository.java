package com.nabd.hms.billing.dues;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DuesRepository {

    /** balance = total - paid - written off; the write-off ledger is summed per invoice. */
    private static final String BALANCE_JOIN = " LEFT JOIN LATERAL (SELECT COALESCE(SUM(w.amount), 0) AS amt FROM invoice_write_offs w " +
            "WHERE w.tenant_id = i.tenant_id AND w.invoice_id = i.id) wo ON true ";

    private final JdbcTemplate jdbc;

    DuesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Filter(Instant from, Instant toExclusive, String status, UUID doctorId, String method, String q) {
    }

    List<InvoiceListItem> list(UUID tenantId, Filter f, Instant cursorAt, UUID cursorId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT i.id, i.invoice_number, i.patient_id, p.name AS patient_name, i.doctor_id, i.status, i.total, i.paid, " +
                "wo.amt, i.created_at FROM invoices i LEFT JOIN patients p ON p.tenant_id = i.tenant_id AND p.id = i.patient_id " + BALANCE_JOIN +
                "WHERE i.tenant_id = ?");
        List<Object> a = new ArrayList<>(List.of(tenantId));
        if (f.from() != null) { sql.append(" AND i.created_at >= ?"); a.add(Timestamp.from(f.from())); }
        if (f.toExclusive() != null) { sql.append(" AND i.created_at < ?"); a.add(Timestamp.from(f.toExclusive())); }
        if (f.status() != null) { sql.append(" AND i.status = ?"); a.add(f.status()); }
        if (f.doctorId() != null) { sql.append(" AND i.doctor_id = ?"); a.add(f.doctorId()); }
        if (f.method() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM invoice_payments pm WHERE pm.tenant_id = i.tenant_id AND pm.invoice_id = i.id AND pm.method = ?)");
            a.add(f.method());
        }
        if (f.q() != null) {
            String like = "%" + f.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (i.invoice_number ILIKE ? OR p.name ILIKE ?)");
            a.add(like);
            a.add(like);
        }
        if (cursorAt != null) { sql.append(" AND (i.created_at, i.id) < (?, ?)"); a.add(Timestamp.from(cursorAt)); a.add(cursorId); }
        sql.append(" ORDER BY i.created_at DESC, i.id DESC LIMIT ?");
        a.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> item(rs), a.toArray());
    }

    private static InvoiceListItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal total = rs.getBigDecimal("total"), paid = rs.getBigDecimal("paid"), wo = rs.getBigDecimal("amt");
        String doctor = rs.getString("doctor_id"), patient = rs.getString("patient_id");
        return new InvoiceListItem(UUID.fromString(rs.getString("id")), rs.getString("invoice_number"),
                patient == null ? null : UUID.fromString(patient), rs.getString("patient_name"), doctor == null ? null : UUID.fromString(doctor),
                rs.getString("status"), total, paid, wo, total.subtract(paid).subtract(wo), rs.getTimestamp("created_at").toInstant());
    }

    record OpenInvoice(UUID id, String number, BigDecimal balance) {
    }

    /** One group per patient with a balance; oldest first. {@code lo}/{@code hi} are the age window in clinic days (hi null = open ended). */
    List<DuesRow> dues(UUID tenantId, java.time.LocalDate today, String zone, Instant oldestBefore, Instant oldestFrom, Instant cursorAt, UUID cursorPatient, int limit) {
        StringBuilder sql = new StringBuilder("SELECT g.patient_id, p.name, g.total_due, g.oldest, g.numbers, " +
                "(SELECT max(r.created_at) FROM dues_reminders r WHERE r.tenant_id = g.tenant_id AND r.patient_id = g.patient_id) AS last_reminded " +
                "FROM (SELECT i.tenant_id, i.patient_id, SUM(i.total - i.paid - wo.amt) AS total_due, MIN(i.created_at) AS oldest, " +
                "array_agg(i.invoice_number ORDER BY i.created_at) AS numbers FROM invoices i " + BALANCE_JOIN +
                "WHERE i.tenant_id = ? AND i.status IN ('unpaid','partial') AND i.patient_id IS NOT NULL AND i.total - i.paid - wo.amt > 0 " +
                "GROUP BY i.tenant_id, i.patient_id) g JOIN patients p ON p.tenant_id = g.tenant_id AND p.id = g.patient_id WHERE true");
        List<Object> a = new ArrayList<>(List.of(tenantId));
        if (oldestBefore != null) { sql.append(" AND g.oldest < ?"); a.add(Timestamp.from(oldestBefore)); }
        if (oldestFrom != null) { sql.append(" AND g.oldest >= ?"); a.add(Timestamp.from(oldestFrom)); }
        if (cursorAt != null) { sql.append(" AND (g.oldest, g.patient_id) > (?, ?)"); a.add(Timestamp.from(cursorAt)); a.add(cursorPatient); }
        sql.append(" ORDER BY g.oldest, g.patient_id LIMIT ?");
        a.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Timestamp lr = rs.getTimestamp("last_reminded");
            long days = java.time.temporal.ChronoUnit.DAYS.between(rs.getTimestamp("oldest").toInstant().atZone(java.time.ZoneId.of(zone)).toLocalDate(), today);
            return new DuesRow(UUID.fromString(rs.getString("patient_id")), rs.getString("name"), rs.getBigDecimal("total_due"), days,
                    DuesService.bucketOf(days), List.of((String[]) rs.getArray("numbers").getArray()), lr == null ? null : lr.toInstant(),
                    rs.getTimestamp("oldest").toInstant());
        }, a.toArray());
    }

    List<OpenInvoice> openInvoices(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT i.id, i.invoice_number, i.total - i.paid - wo.amt AS bal FROM invoices i " + BALANCE_JOIN +
                "WHERE i.tenant_id = ? AND i.patient_id = ? AND i.status IN ('unpaid','partial') AND i.total - i.paid - wo.amt > 0 ORDER BY i.created_at",
                (rs, n) -> new OpenInvoice(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getBigDecimal(3)), tenantId, patientId);
    }

    Instant lastReminder(UUID tenantId, UUID patientId) {
        return jdbc.queryForObject("SELECT max(created_at) FROM dues_reminders WHERE tenant_id = ? AND patient_id = ?", Instant.class, tenantId, patientId);
    }

    String clinicName(UUID tenantId) {
        return jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
    }

    void insertReminder(UUID id, UUID tenantId, UUID patientId, List<UUID> invoiceIds, String channel, UUID sentBy) {
        jdbc.update("INSERT INTO dues_reminders (id, tenant_id, patient_id, invoice_ids, channel, sent_by) VALUES (?,?,?,?,?,?)",
                id, tenantId, patientId, invoiceIds.toArray(new UUID[0]), channel, sentBy);
    }

    /** The invoice row locked, so two write-offs on one invoice serialise. */
    Optional<InvoiceListItem> lockForWriteOff(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT i.id, i.invoice_number, i.patient_id, NULL::text AS patient_name, i.doctor_id, i.status, i.total, i.paid, wo.amt, i.created_at " +
                "FROM invoices i " + BALANCE_JOIN + "WHERE i.tenant_id = ? AND i.id = ? FOR UPDATE OF i", (rs, n) -> item(rs), tenantId, invoiceId).stream().findFirst();
    }

    void insertWriteOff(UUID tenantId, UUID invoiceId, BigDecimal amount, String reason, String note, UUID by) {
        jdbc.update("INSERT INTO invoice_write_offs (tenant_id, invoice_id, amount, reason_code, note, written_by) VALUES (?,?,?,?,?,?)",
                tenantId, invoiceId, amount, reason, note, by);
    }
}
