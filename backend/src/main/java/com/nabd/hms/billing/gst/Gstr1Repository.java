package com.nabd.hms.billing.gst;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FIN-011: reads of the GSTR-1 export. Stored figures only (invoice_line_taxes, credit_note_lines.taxes); void invoices are left out. */
@Repository
class Gstr1Repository {

    /** Tax components of one line pivoted to the return's columns; anything that is not IGST/CGST/SGST/UTGST lands in cess. */
    private static final String PIVOT = "COALESCE(SUM(%1$s) FILTER (WHERE %2$s = 'IGST'), 0) AS igst, "
            + "COALESCE(SUM(%1$s) FILTER (WHERE %2$s = 'CGST'), 0) AS cgst, "
            + "COALESCE(SUM(%1$s) FILTER (WHERE %2$s IN ('SGST', 'UTGST')), 0) AS sgst, "
            + "COALESCE(SUM(%1$s) FILTER (WHERE %2$s NOT IN ('IGST', 'CGST', 'SGST', 'UTGST')), 0) AS cess";
    private static final String CATEGORY = "COALESCE(li.tax_category, CASE WHEN li.tax_rate_percent > 0 THEN 'TAXABLE' ELSE 'NIL' END)";

    private final JdbcTemplate jdbc;

    Gstr1Repository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The clinic's GSTIN as the tax engine sees it (V2271); empty when no registration is on file. */
    Optional<String> clinicGstin() {
        return jdbc.query("SELECT gstin FROM billing_clinic_tax_profile() WHERE registered", (rs, i) -> rs.getString(1)).stream()
                .filter(g -> g != null && !g.isBlank()).findFirst();
    }

    /** Lines of the invoices issued in [from, to), oldest invoice first. A line billed before the tax engine (no taxable_minor) counts at its line total. */
    List<Gstr1.Line> invoiceLines(UUID tenantId, Instant from, Instant to, ZoneId zone) {
        return jdbc.query("SELECT i.invoice_number, i.created_at, i.total_minor, i.buyer_gstin, i.document_type, li.sac_hsn, li.quantity, "
                        + CATEGORY + " AS category, li.tax_rate_percent, li.inter_state, COALESCE(li.taxable_minor, round(li.line_total * 100)::bigint) AS taxable, "
                        + String.format(PIVOT, "t.amount_minor", "t.tax_type")
                        + " FROM invoices i JOIN invoice_line_items li ON li.tenant_id = i.tenant_id AND li.invoice_id = i.id "
                        + "LEFT JOIN invoice_line_taxes t ON t.tenant_id = li.tenant_id AND t.line_id = li.id "
                        + "WHERE i.tenant_id = ? AND i.status <> 'void' AND i.created_at >= ? AND i.created_at < ? "
                        + "GROUP BY i.id, li.id ORDER BY i.created_at, i.id, li.display_order, li.id",
                (rs, n) -> new Gstr1.Line(rs.getString("invoice_number"), rs.getTimestamp("created_at").toInstant().atZone(zone).toLocalDate(),
                        rs.getLong("total_minor"), rs.getString("buyer_gstin"), "bill_of_supply".equals(rs.getString("document_type")), null, null,
                        rs.getString("sac_hsn"), rs.getInt("quantity"), rs.getString("category"), rs.getBigDecimal("tax_rate_percent"),
                        rs.getBoolean("inter_state"), rs.getLong("taxable"), rs.getLong("igst"), rs.getLong("cgst"), rs.getLong("sgst"), rs.getLong("cess")),
                tenantId, Timestamp.from(from), Timestamp.from(to));
    }

    /** Reversed lines of the credit notes issued in [from, to); class, rate and SAC/HSN are those of the invoice line they reverse. */
    List<Gstr1.Line> creditLines(UUID tenantId, Instant from, Instant to, ZoneId zone) {
        return jdbc.query("SELECT c.number, c.issued_at, c.total_minor, i.buyer_gstin, i.document_type, i.invoice_number, i.created_at, li.sac_hsn, "
                        + "l.quantity, " + CATEGORY + " AS category, li.tax_rate_percent, li.inter_state, l.taxable_minor AS taxable, x.igst, x.cgst, x.sgst, x.cess "
                        + "FROM credit_notes c JOIN credit_note_lines l ON l.tenant_id = c.tenant_id AND l.credit_note_id = c.id "
                        + "JOIN invoices i ON i.tenant_id = c.tenant_id AND i.id = c.invoice_id "
                        + "JOIN invoice_line_items li ON li.tenant_id = l.tenant_id AND li.id = l.invoice_line_id "
                        + "CROSS JOIN LATERAL (SELECT " + String.format(PIVOT, "(e->>'amountMinor')::bigint", "e->>'type'")
                        + " FROM jsonb_array_elements(l.taxes) e) x "
                        + "WHERE c.tenant_id = ? AND c.issued_at >= ? AND c.issued_at < ? ORDER BY c.issued_at, c.id, l.id",
                (rs, n) -> new Gstr1.Line(rs.getString("number"), rs.getTimestamp("issued_at").toInstant().atZone(zone).toLocalDate(),
                        rs.getLong("total_minor"), rs.getString("buyer_gstin"), "bill_of_supply".equals(rs.getString("document_type")),
                        rs.getString("invoice_number"), rs.getTimestamp("created_at").toInstant().atZone(zone).toLocalDate(),
                        rs.getString("sac_hsn"), rs.getInt("quantity"), rs.getString("category"), rs.getBigDecimal("tax_rate_percent"),
                        rs.getBoolean("inter_state"), rs.getLong("taxable"), rs.getLong("igst"), rs.getLong("cgst"), rs.getLong("sgst"), rs.getLong("cess")),
                tenantId, Timestamp.from(from), Timestamp.from(to));
    }

    /** The invoice register and the credit-note register of [from, to): header sums, independent of the line reads above. */
    Gstr1.Register register(UUID tenantId, Instant from, Instant to) {
        long[] inv = jdbc.queryForObject("SELECT count(*), COALESCE(SUM(subtotal_minor - discount_minor), 0), COALESCE(SUM(tax_minor), 0), "
                        + "COALESCE(SUM(round_off_minor), 0), COALESCE(SUM(total_minor), 0) FROM invoices "
                        + "WHERE tenant_id = ? AND status <> 'void' AND created_at >= ? AND created_at < ?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}, tenantId, Timestamp.from(from), Timestamp.from(to));
        long[] cn = jdbc.queryForObject("SELECT count(*), COALESCE(SUM(taxable_minor), 0), COALESCE(SUM(tax_minor), 0), COALESCE(SUM(round_off_minor), 0), "
                        + "COALESCE(SUM(total_minor), 0) FROM credit_notes WHERE tenant_id = ? AND issued_at >= ? AND issued_at < ?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}, tenantId, Timestamp.from(from), Timestamp.from(to));
        return new Gstr1.Register((int) inv[0], inv[1], inv[2], inv[3], inv[4], (int) cn[0], cn[1], cn[2], cn[3], cn[4]);
    }
}
