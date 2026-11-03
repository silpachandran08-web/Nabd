package com.nabd.hms.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** FIN-018: the leakage ledger: discounts, below-list overrides, write-offs and approved refunds in [from, to). Void invoices are left out. */
@Repository
class LeakageRepository {

    /** amount is in major units (amountMinor set instead for refunds). */
    record Row(String type, UUID id, Instant at, UUID invoiceId, String invoiceNumber, String currency, BigDecimal amount, Long amountMinor,
               String reasonCode, UUID appliedBy, UUID approverId, String approvalMethod) {
    }

    private final JdbcTemplate jdbc;

    LeakageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> rows(UUID tenantId, Instant from, Instant to, String type) {
        // shortcut: invoice_line_items has no timestamp, so an override is dated by its invoice; add one if extended invoices skew the day.
        String sql = """
                SELECT * FROM (
                  SELECT 'discount' AS type, d.id, d.created_at AS at, d.invoice_id, i.invoice_number, i.currency, d.amount, NULL::bigint AS amount_minor,
                         d.reason_code, d.applied_by, COALESCE(a.approver_id, d.applied_by) AS approver_id, COALESCE(a.method, 'direct') AS method
                  FROM invoice_discounts d JOIN invoices i ON i.id = d.invoice_id AND i.tenant_id = d.tenant_id
                  LEFT JOIN discount_approvals a ON a.id = d.approval_id AND a.tenant_id = d.tenant_id
                  WHERE d.tenant_id = ? AND i.status <> 'void' AND d.created_at >= ? AND d.created_at < ?
                  UNION ALL
                  SELECT 'override', li.id, i.created_at, li.invoice_id, i.invoice_number, i.currency, (li.list_unit_price - li.unit_price) * li.quantity, NULL,
                         li.override_reason_code, li.overridden_by, COALESCE(a.approver_id, li.overridden_by), COALESCE(a.method, 'direct')
                  FROM invoice_line_items li JOIN invoices i ON i.id = li.invoice_id AND i.tenant_id = li.tenant_id
                  LEFT JOIN LATERAL (SELECT x.approver_id, x.method FROM discount_approvals x WHERE x.tenant_id = li.tenant_id AND x.invoice_id = li.invoice_id
                                     AND x.consumed_at IS NOT NULL ORDER BY x.consumed_at DESC LIMIT 1) a ON true
                  WHERE li.tenant_id = ? AND i.status <> 'void' AND li.overridden_by IS NOT NULL AND li.charge_catalogue_id IS NOT NULL
                    AND li.unit_price < li.list_unit_price AND i.created_at >= ? AND i.created_at < ?
                  UNION ALL
                  SELECT 'write_off', w.id, w.created_at, w.invoice_id, i.invoice_number, i.currency, w.amount, NULL,
                         w.reason_code, w.written_by, w.written_by, 'direct'
                  FROM invoice_write_offs w JOIN invoices i ON i.id = w.invoice_id AND i.tenant_id = w.tenant_id
                  WHERE w.tenant_id = ? AND w.created_at >= ? AND w.created_at < ?
                  UNION ALL
                  SELECT 'refund', r.id, r.decided_at, r.invoice_id, i.invoice_number, r.currency, NULL, r.amount_minor,
                         r.reason_code, r.requested_by, r.approved_by, 'maker_checker'
                  FROM refund_requests r JOIN invoices i ON i.id = r.invoice_id AND i.tenant_id = r.tenant_id
                  WHERE r.tenant_id = ? AND r.status = 'approved' AND r.decided_at >= ? AND r.decided_at < ?
                ) x WHERE (?::text IS NULL OR x.type = ?::text) ORDER BY x.at, x.id""";
        Timestamp f = Timestamp.from(from);
        Timestamp t = Timestamp.from(to);
        return jdbc.query(sql, (rs, i) -> new Row(rs.getString("type"), rs.getObject("id", UUID.class), rs.getTimestamp("at").toInstant(),
                        rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"), rs.getString("currency"), rs.getBigDecimal("amount"),
                        (Long) rs.getObject("amount_minor"), rs.getString("reason_code"), rs.getObject("applied_by", UUID.class),
                        rs.getObject("approver_id", UUID.class), rs.getString("method")),
                tenantId, f, t, tenantId, f, t, tenantId, f, t, tenantId, f, t, type, type);
    }
}
