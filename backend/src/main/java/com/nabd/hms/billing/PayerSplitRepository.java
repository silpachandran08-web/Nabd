package com.nabd.hms.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FIN-036: the payer / patient split columns of an invoice and its lines, and the payer payment rows. */
@Repository
class PayerSplitRepository {

    /** A line's value after discount, tax included (taxable + its stored tax components), in minor units. */
    record Line(UUID id, long grossMinor, Long patientShareMinor, long payerShareMinor) {
    }

    record Invoice(UUID id, UUID payerId, String currency, String status, BigDecimal total, BigDecimal paid, BigDecimal payerShare, BigDecimal payerPaid,
                   String shortfallStatus) {
        BigDecimal pendingDecision() {
            return shortfallStatus == null ? BigDecimal.ZERO.setScale(payerShare.scale()) : payerShare.subtract(payerPaid);
        }
    }

    private final JdbcTemplate jdbc;

    PayerSplitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Invoice> find(UUID tenantId, UUID invoiceId, boolean lock) {
        return jdbc.query("SELECT id, payer_id, currency, status, total, paid, payer_share, payer_paid, payer_shortfall_status FROM invoices " +
                        "WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new Invoice(rs.getObject("id", UUID.class), rs.getObject("payer_id", UUID.class), rs.getString("currency"), rs.getString("status"),
                        rs.getBigDecimal("total"), rs.getBigDecimal("paid"), rs.getBigDecimal("payer_share"), rs.getBigDecimal("payer_paid"),
                        rs.getString("payer_shortfall_status")), tenantId, invoiceId).stream().findFirst();
    }

    boolean visitBilled(UUID tenantId, UUID visitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM invoices WHERE tenant_id = ? AND queue_entry_id = ?)",
                Boolean.class, tenantId, visitId));
    }

    List<Line> lines(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT li.id, li.patient_share_minor, li.payer_share_minor, COALESCE(li.taxable_minor, 0) + COALESCE((SELECT SUM(t.amount_minor) " +
                        "FROM invoice_line_taxes t WHERE t.tenant_id = li.tenant_id AND t.line_id = li.id), 0) AS gross_minor " +
                        "FROM invoice_line_items li WHERE li.tenant_id = ? AND li.invoice_id = ? ORDER BY li.display_order",
                (rs, i) -> new Line(rs.getObject("id", UUID.class), rs.getLong("gross_minor"), (Long) rs.getObject("patient_share_minor"),
                        rs.getLong("payer_share_minor")), tenantId, invoiceId);
    }

    void setLineShares(UUID tenantId, UUID lineId, long patientMinor, long payerMinor) {
        jdbc.update("UPDATE invoice_line_items SET patient_share_minor = ?, payer_share_minor = ? WHERE tenant_id = ? AND id = ?",
                patientMinor, payerMinor, tenantId, lineId);
    }

    void setSplit(UUID tenantId, UUID invoiceId, UUID payerId, BigDecimal payerShare, String status) {
        jdbc.update("UPDATE invoices SET payer_id = ?, payer_share = ?, status = ?, version = version + 1 WHERE tenant_id = ? AND id = ?",
                payerId, payerShare, status, tenantId, invoiceId);
    }

    UUID insertPayment(UUID tenantId, UUID invoiceId, UUID payerId, BigDecimal amount, String reference, UUID staffId) {
        return jdbc.queryForObject("INSERT INTO invoice_payer_payments (tenant_id, invoice_id, payer_id, amount, reference, recorded_by) " +
                "VALUES (?,?,?,?,?,?) RETURNING id", UUID.class, tenantId, invoiceId, payerId, amount, reference, staffId);
    }

    /** payer_paid is always the sum of the payer payment rows; a shortfall left after a payment waits for a decision. */
    void syncPayerPaid(UUID tenantId, UUID invoiceId) {
        jdbc.update("UPDATE invoices i SET payer_paid = s.paid, payer_shortfall_status = CASE WHEN s.paid < i.payer_share THEN 'pending_decision' END, " +
                        "version = version + 1 FROM (SELECT COALESCE(SUM(amount), 0) AS paid FROM invoice_payer_payments WHERE tenant_id = ? AND invoice_id = ?) s " +
                        "WHERE i.tenant_id = ? AND i.id = ?", tenantId, invoiceId, tenantId, invoiceId);
    }
}
