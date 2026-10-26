package com.nabd.hms.billing.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class InvoiceDocumentRepository {

    private final JdbcTemplate jdbc;

    InvoiceDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    InvoiceDocumentText.Supplier supplier(UUID tenantId, java.time.ZoneId zone) {
        return jdbc.query("SELECT name, tax_id, tax_id_type FROM tenants WHERE id = ?", (rs, i) -> {
            String gstin = "GSTIN".equals(rs.getString("tax_id_type")) ? rs.getString("tax_id") : null;
            return new InvoiceDocumentText.Supplier(rs.getString("name"), gstin,
                    gstin != null && gstin.length() >= 2 ? gstin.substring(0, 2) : null, zone);
        }, tenantId).stream().findFirst().orElseThrow();
    }

    List<String> sacHsn(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT sac_hsn FROM invoice_line_items WHERE tenant_id = ? AND invoice_id = ? ORDER BY display_order",
                (rs, i) -> rs.getString(1), tenantId, invoiceId);
    }

    int invoiceVersion(UUID tenantId, UUID invoiceId) {
        return jdbc.queryForObject("SELECT version FROM invoices WHERE tenant_id = ? AND id = ?", Integer.class, tenantId, invoiceId);
    }

    /** The newest receipt number on the invoice, null when it has no payment. */
    String latestReceiptNumber(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT receipt_number FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ? ORDER BY recorded_at DESC, id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), tenantId, invoiceId).stream().findFirst().orElse(null);
    }

    Optional<byte[]> find(UUID tenantId, UUID invoiceId, String kind, String layout, int version) {
        return jdbc.query("SELECT content FROM billing_documents WHERE tenant_id = ? AND invoice_id = ? AND kind = ? AND layout = ? AND version = ?",
                (rs, i) -> rs.getBytes(1), tenantId, invoiceId, kind, layout, version).stream().findFirst();
    }

    /** True when this call stored the document; a concurrent render of the same version wins silently. */
    boolean store(UUID tenantId, UUID invoiceId, String kind, String layout, int version, String objectKey, String sha256, byte[] content) {
        return jdbc.update("INSERT INTO billing_documents (tenant_id, invoice_id, kind, layout, version, object_key, sha256, content) VALUES (?,?,?,?,?,?,?,?) " +
                "ON CONFLICT (tenant_id, invoice_id, kind, layout, version) DO NOTHING", tenantId, invoiceId, kind, layout, version, objectKey, sha256, content) == 1;
    }
}
