package com.nabd.hms.billing.einvoice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FIN-011: einvoice_documents, einvoice_settings and the invoice reads the IRP payload needs. Callers hold the tenant transaction. */
@Repository
class EinvoiceRepository {

    record InvoiceHead(UUID id, String number, Instant createdAt, String buyerGstin, String documentType, String status, long roundOffMinor, long totalMinor) {
    }

    record Due(UUID id, UUID invoiceId, int attempts) {
    }

    record Doc(UUID id, UUID invoiceId, String invoiceNumber, String status, String irn, String ackNo, Instant ackAt, int attempts, Instant nextAttemptAt,
               String errors, Instant createdAt) {
    }

    private static final String DOC = "SELECT d.id, d.document_id, i.invoice_number, d.status, d.external_id, d.ack_ref, d.ack_at, d.attempts, d.next_attempt_at, "
            + "d.errors::text AS errors, d.created_at FROM einvoice_documents d JOIN invoices i ON i.tenant_id = d.tenant_id AND i.id = d.document_id "
            + "WHERE d.tenant_id = ? AND d.regime = ? AND d.document_type = 'invoice' ";

    private final JdbcTemplate jdbc;

    EinvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean irpEnabled(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM einvoice_settings WHERE tenant_id = ? AND irp_enabled)", Boolean.class, tenantId));
    }

    void setIrpEnabled(UUID tenantId, boolean enabled, UUID staffId) {
        jdbc.update("INSERT INTO einvoice_settings (tenant_id, irp_enabled, updated_by) VALUES (?,?,?) ON CONFLICT (tenant_id) DO UPDATE SET "
                + "irp_enabled = EXCLUDED.irp_enabled, updated_by = EXCLUDED.updated_by, updated_at = now()", tenantId, enabled, staffId);
    }

    Optional<InvoiceHead> invoice(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT id, invoice_number, created_at, buyer_gstin, document_type, status, round_off_minor, total_minor FROM invoices "
                        + "WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new InvoiceHead(rs.getObject("id", UUID.class), rs.getString("invoice_number"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("buyer_gstin"), rs.getString("document_type"), rs.getString("status"), rs.getLong("round_off_minor"),
                        rs.getLong("total_minor")), tenantId, invoiceId).stream().findFirst();
    }

    /** {name, GSTIN} of the clinic: the name from its own row, the GSTIN as the tax engine sees it (V2271). */
    String[] seller(UUID tenantId) {
        String name = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
        String gstin = jdbc.query("SELECT gstin FROM billing_clinic_tax_profile()", (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        return new String[]{name, gstin};
    }

    List<Inv01.Item> items(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT li.charge_name, li.sac_hsn, li.quantity, li.discount_allocated_minor, "
                        + "COALESCE(li.taxable_minor, round(li.line_total * 100)::bigint) AS taxable, li.tax_rate_percent, "
                        + "COALESCE(SUM(t.amount_minor) FILTER (WHERE t.tax_type = 'IGST'), 0) AS igst, "
                        + "COALESCE(SUM(t.amount_minor) FILTER (WHERE t.tax_type = 'CGST'), 0) AS cgst, "
                        + "COALESCE(SUM(t.amount_minor) FILTER (WHERE t.tax_type IN ('SGST', 'UTGST')), 0) AS sgst, "
                        + "COALESCE(SUM(t.amount_minor) FILTER (WHERE t.tax_type NOT IN ('IGST', 'CGST', 'SGST', 'UTGST')), 0) AS cess "
                        + "FROM invoice_line_items li LEFT JOIN invoice_line_taxes t ON t.tenant_id = li.tenant_id AND t.line_id = li.id "
                        + "WHERE li.tenant_id = ? AND li.invoice_id = ? GROUP BY li.id ORDER BY li.display_order, li.id",
                (rs, i) -> new Inv01.Item(rs.getString("charge_name"), rs.getString("sac_hsn"), rs.getInt("quantity"), rs.getLong("discount_allocated_minor"),
                        rs.getLong("taxable"), rs.getBigDecimal("tax_rate_percent"), rs.getLong("igst"), rs.getLong("cgst"), rs.getLong("sgst"), rs.getLong("cess")),
                tenantId, invoiceId);
    }

    Inv01.Invoice payloadSource(UUID tenantId, InvoiceHead inv, ZoneId zone) {
        String[] seller = seller(tenantId);
        return new Inv01.Invoice(inv.number(), inv.createdAt().atZone(zone).toLocalDate(), seller[1], seller[0], inv.buyerGstin(), inv.roundOffMinor(),
                inv.totalMinor(), items(tenantId, inv.id()));
    }

    /** A submission waiting for its first attempt; a second call for the same invoice changes nothing. */
    void enqueue(UUID tenantId, UUID invoiceId, String regime, Instant now) {
        jdbc.update("INSERT INTO einvoice_documents (tenant_id, document_type, document_id, regime, status, next_attempt_at) VALUES (?, 'invoice', ?, ?, 'pending', ?) "
                + "ON CONFLICT (tenant_id, regime, document_type, document_id) DO NOTHING", tenantId, invoiceId, regime, Timestamp.from(now));
    }

    /** Due submissions, locked for this transaction; a row another worker holds is skipped. */
    List<Due> lockDue(UUID tenantId, String regime, Instant now, int limit) {
        return jdbc.query("SELECT id, document_id, attempts FROM einvoice_documents WHERE tenant_id = ? AND regime = ? AND document_type = 'invoice' "
                        + "AND status = 'pending' AND next_attempt_at <= ? ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getInt("attempts")),
                tenantId, regime, Timestamp.from(now), limit);
    }

    /** The claim: the attempt is counted and the next one scheduled BEFORE the call, so a crash mid-call is retried by itself. */
    void scheduleRetry(UUID tenantId, UUID id, int attempts, Instant nextAttemptAt) {
        jdbc.update("UPDATE einvoice_documents SET attempts = ?, next_attempt_at = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                attempts, Timestamp.from(nextAttemptAt), tenantId, id);
    }

    int accepted(UUID tenantId, UUID id, IrpPort.Accepted a) {
        return jdbc.update("UPDATE einvoice_documents SET status = 'accepted', external_id = ?, ack_ref = ?, ack_at = ?, qr_payload = ?, errors = NULL, "
                        + "next_attempt_at = NULL, updated_at = now() WHERE tenant_id = ? AND id = ? AND status = 'pending'",
                a.irn(), a.ackNo(), a.ackAt() == null ? null : Timestamp.from(a.ackAt()), a.signedQr(), tenantId, id);
    }

    /** IRP unavailable: the row stays pending with the retry time the claim already set. */
    void failed(UUID tenantId, UUID id, String errorsJson) {
        jdbc.update("UPDATE einvoice_documents SET errors = ?::jsonb, updated_at = now() WHERE tenant_id = ? AND id = ? AND status = 'pending'", errorsJson, tenantId, id);
    }

    void rejected(UUID tenantId, UUID id, String errorsJson) {
        jdbc.update("UPDATE einvoice_documents SET status = 'rejected', errors = ?::jsonb, next_attempt_at = NULL, updated_at = now() "
                + "WHERE tenant_id = ? AND id = ? AND status = 'pending'", errorsJson, tenantId, id);
    }

    /** The invoice was voided before anything reached the IRP. */
    void cancelUnsent(UUID tenantId, UUID id) {
        jdbc.update("UPDATE einvoice_documents SET status = 'cancelled', next_attempt_at = NULL, updated_at = now() WHERE tenant_id = ? AND id = ? AND status = 'pending'",
                tenantId, id);
    }

    void requeue(UUID tenantId, UUID id, Instant now) {
        jdbc.update("UPDATE einvoice_documents SET status = 'pending', next_attempt_at = ?, updated_at = now() WHERE tenant_id = ? AND id = ? "
                + "AND status IN ('pending', 'rejected')", Timestamp.from(now), tenantId, id);
    }

    /** The printed invoice is stored per invoice version: moving the version makes the next print carry the IRN. */
    void bumpInvoiceVersion(UUID tenantId, UUID invoiceId) {
        jdbc.update("UPDATE invoices SET version = version + 1 WHERE tenant_id = ? AND id = ?", tenantId, invoiceId);
    }

    Optional<Doc> find(UUID tenantId, String regime, UUID invoiceId) {
        return jdbc.query(DOC + "AND d.document_id = ?", this::doc, tenantId, regime, invoiceId).stream().findFirst();
    }

    List<Doc> latest(UUID tenantId, String regime, int limit) {
        return jdbc.query(DOC + "ORDER BY d.created_at DESC, d.id DESC LIMIT ?", this::doc, tenantId, regime, limit);
    }

    private Doc doc(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp ack = rs.getTimestamp("ack_at"), next = rs.getTimestamp("next_attempt_at");
        return new Doc(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getString("invoice_number"), rs.getString("status"),
                rs.getString("external_id"), rs.getString("ack_ref"), ack == null ? null : ack.toInstant(), rs.getInt("attempts"),
                next == null ? null : next.toInstant(), rs.getString("errors"), rs.getTimestamp("created_at").toInstant());
    }
}
