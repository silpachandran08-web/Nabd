package com.nabd.hms.billing;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.billing.tax.TaxEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** FIN-013: refund requests, credit notes and the refund payment rows. Callers hold the invoice row lock; tenant is bound by the transaction. */
@Repository
class RefundRepository {

    record InvoiceHead(UUID id, String number, String status, String currency, long totalMinor, long roundOffMinor) {
    }

    record LineHead(UUID id, String chargeCode, int quantity, Long taxableMinor, BigDecimal lineTotal, BigDecimal ratePercent) {
    }

    record PaymentHead(UUID id, String method, long amountMinor, long refundedMinor) {
    }

    record RequestRow(UUID id, UUID invoiceId, String invoiceNumber, long amountMinor, String currency, String reasonCode, String note,
                      String status, String linesJson, String refundToJson, UUID requestedBy, UUID decidedBy, Instant decidedAt, Instant createdAt) {
    }

    private static final String REQ_COLS = "r.id, r.invoice_id, i.invoice_number, r.amount_minor, r.currency, r.reason_code, r.note, r.status, " +
            "r.lines::text AS lines, r.refund_to::text AS refund_to, r.requested_by, r.approved_by, r.decided_at, r.created_at";
    private static final String REQ_FROM = " FROM refund_requests r JOIN invoices i ON i.id = r.invoice_id AND i.tenant_id = r.tenant_id ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.nabd.hms.pharmacy.api.StockCommands stock;

    RefundRepository(JdbcTemplate jdbc, ObjectMapper json, com.nabd.hms.pharmacy.api.StockCommands stock) {
        this.jdbc = jdbc;
        this.json = json;
        this.stock = stock;
    }

    Optional<InvoiceHead> lockInvoice(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT id, invoice_number, status, currency, total_minor, round_off_minor FROM invoices WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, i) -> new InvoiceHead(rs.getObject("id", UUID.class), rs.getString("invoice_number"), rs.getString("status"),
                        rs.getString("currency"), rs.getLong("total_minor"), rs.getLong("round_off_minor")), tenantId, invoiceId).stream().findFirst();
    }

    List<LineHead> lines(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT id, charge_code, quantity, taxable_minor, line_total, tax_rate_percent FROM invoice_line_items " +
                        "WHERE tenant_id = ? AND invoice_id = ? ORDER BY display_order, id",
                (rs, i) -> new LineHead(rs.getObject("id", UUID.class), rs.getString("charge_code"), rs.getInt("quantity"),
                        (Long) rs.getObject("taxable_minor"), rs.getBigDecimal("line_total"), rs.getBigDecimal("tax_rate_percent")), tenantId, invoiceId);
    }

    /** Invoiced tax components per line id. */
    Map<UUID, List<TaxEngine.Component>> lineTaxes(UUID tenantId, UUID invoiceId) {
        Map<UUID, List<TaxEngine.Component>> out = new HashMap<>();
        jdbc.query("SELECT t.line_id, t.tax_type, t.rate, t.taxable_minor, t.amount_minor FROM invoice_line_taxes t " +
                        "JOIN invoice_line_items li ON li.id = t.line_id AND li.tenant_id = t.tenant_id " +
                        "WHERE t.tenant_id = ? AND li.invoice_id = ? ORDER BY t.id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.computeIfAbsent(rs.getObject("line_id", UUID.class), k -> new ArrayList<>())
                        .add(new TaxEngine.Component(rs.getString("tax_type"), rs.getBigDecimal("rate"), rs.getLong("taxable_minor"), rs.getLong("amount_minor"))),
                tenantId, invoiceId);
        return out;
    }

    record Reversed(UUID lineId, int quantity, long taxableMinor, List<TaxEngine.Component> taxes) {
    }

    /** What earlier credit notes of this invoice already reversed, per invoice line. */
    List<Reversed> reversed(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT l.invoice_line_id, l.quantity, l.taxable_minor, l.taxes::text AS taxes FROM credit_note_lines l " +
                        "JOIN credit_notes c ON c.id = l.credit_note_id AND c.tenant_id = l.tenant_id WHERE l.tenant_id = ? AND c.invoice_id = ?",
                (rs, i) -> new Reversed(rs.getObject("invoice_line_id", UUID.class), rs.getInt("quantity"), rs.getLong("taxable_minor"),
                        readComponents(rs.getString("taxes"))), tenantId, invoiceId);
    }

    long sumCredits(UUID tenantId, UUID invoiceId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(total_minor), 0) FROM credit_notes WHERE tenant_id = ? AND invoice_id = ?", Long.class, tenantId, invoiceId);
    }

    long sumCreditRoundOff(UUID tenantId, UUID invoiceId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(round_off_minor), 0) FROM credit_notes WHERE tenant_id = ? AND invoice_id = ?", Long.class, tenantId, invoiceId);
    }

    List<PaymentHead> payments(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT p.id, p.method, p.amount_minor, COALESCE((SELECT SUM(-r.amount_minor) FROM invoice_payments r " +
                        "WHERE r.tenant_id = p.tenant_id AND r.refund_of_payment_id = p.id), 0) AS refunded " +
                        "FROM invoice_payments p WHERE p.tenant_id = ? AND p.invoice_id = ? AND p.kind = 'payment' ORDER BY p.recorded_at, p.id",
                (rs, i) -> new PaymentHead(rs.getObject("id", UUID.class), rs.getString("method"), rs.getLong("amount_minor"), rs.getLong("refunded")),
                tenantId, invoiceId);
    }

    long netPaidMinor(UUID tenantId, UUID invoiceId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ?", Long.class, tenantId, invoiceId);
    }

    boolean hasPending(UUID tenantId, UUID invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM refund_requests WHERE tenant_id = ? AND invoice_id = ? AND status = 'pending')",
                Boolean.class, tenantId, invoiceId));
    }

    UUID insertRequest(UUID tenantId, UUID invoiceId, long amountMinor, String currency, String reason, String note, Object lines, Object refundTo, UUID staffId) {
        return jdbc.queryForObject("INSERT INTO refund_requests (tenant_id, invoice_id, amount_minor, currency, reason_code, note, lines, refund_to, requested_by) " +
                        "VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?) RETURNING id", UUID.class,
                tenantId, invoiceId, amountMinor, currency, reason, note, lines == null ? null : toJson(lines), toJson(refundTo), staffId);
    }

    Optional<RequestRow> findRequest(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + REQ_COLS + REQ_FROM + "WHERE r.tenant_id = ? AND r.id = ?", this::mapRequest, tenantId, id).stream().findFirst();
    }

    /** Keyset page, newest first; (beforeAt, beforeId) is the previous page's last row. */
    List<RequestRow> listRequests(UUID tenantId, String status, Instant beforeAt, UUID beforeId, int limit) {
        return jdbc.query("SELECT " + REQ_COLS + REQ_FROM + "WHERE r.tenant_id = ? AND (?::text IS NULL OR r.status = ?) " +
                        "AND (?::timestamptz IS NULL OR (r.created_at, r.id) < (?::timestamptz, ?::uuid)) ORDER BY r.created_at DESC, r.id DESC LIMIT ?",
                this::mapRequest, tenantId, status, status, beforeAt == null ? null : Timestamp.from(beforeAt),
                beforeAt == null ? null : Timestamp.from(beforeAt), beforeId, limit);
    }

    int decide(UUID tenantId, UUID id, String status, UUID staffId, String decisionNote) {
        return jdbc.update("UPDATE refund_requests SET status = ?, approved_by = ?, decision_note = ?, decided_at = now() " +
                "WHERE tenant_id = ? AND id = ? AND status = 'pending'", status, staffId, decisionNote, tenantId, id);
    }

    UUID insertCreditNote(UUID tenantId, UUID invoiceId, UUID requestId, UUID seriesId, String number, String currency,
                          CreditNoteMath.Note note, UUID staffId) {
        return jdbc.queryForObject("INSERT INTO credit_notes (tenant_id, invoice_id, refund_request_id, series_id, number, currency, taxable_minor, tax_minor, " +
                        "round_off_minor, total_minor, issued_by) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id", UUID.class,
                tenantId, invoiceId, requestId, seriesId, number, currency, note.taxableMinor(), note.taxMinor(), note.roundOffMinor(), note.totalMinor(), staffId);
    }

    void insertCreditNoteLine(UUID tenantId, UUID creditNoteId, UUID invoiceLineId, CreditNoteMath.Reversal r) {
        jdbc.update("INSERT INTO credit_note_lines (tenant_id, credit_note_id, invoice_line_id, quantity, taxable_minor, tax_minor, taxes) VALUES (?,?,?,?,?,?,?::jsonb)",
                tenantId, creditNoteId, invoiceLineId, r.quantity(), r.taxableMinor(), r.taxMinor(), toJson(r.taxes()));
    }

    /** A negative payment row against the payment it returns money to; currency and amount_minor are filled by the DB trigger. */
    void insertRefundPayment(UUID tenantId, UUID invoiceId, String method, BigDecimal negativeAmount, UUID staffId, UUID requestId, UUID ofPayment) {
        jdbc.update("INSERT INTO invoice_payments (tenant_id, invoice_id, method, amount, recorded_by, kind, refund_request_id, refund_of_payment_id) " +
                "VALUES (?,?,?,?,?,'refund',?,?)", tenantId, invoiceId, method, negativeAmount, staffId, requestId, ofPayment);
    }

    void syncInvoice(UUID tenantId, UUID invoiceId, String status) {
        jdbc.update("UPDATE invoices SET paid = (SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ?), " +
                "status = ?, version = version + 1 WHERE tenant_id = ? AND id = ?", tenantId, invoiceId, status, tenantId, invoiceId);
    }

    /** Puts {@code qty} of a stocked pharmacy line back on the shelf with an immutable 'restock' movement; false when the line is not a stocked item. */
    boolean restock(UUID tenantId, UUID invoiceId, UUID lineId, int qty, String reason, UUID staffId) {
        List<UUID> item = jdbc.query("SELECT c.id FROM invoice_line_items li JOIN charge_catalogue c ON c.tenant_id = li.tenant_id AND c.code = li.charge_code " +
                "AND c.stock_qty IS NOT NULL WHERE li.tenant_id = ? AND li.id = ?", (rs, i) -> rs.getObject("id", UUID.class), tenantId, lineId);
        if (item.isEmpty()) return false;
        stock.returnIn(item.get(0), qty, invoiceId, staffId, reason);
        return true;
    }

    private RequestRow mapRequest(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new RequestRow(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("reason_code"), rs.getString("note"), rs.getString("status"),
                rs.getString("lines"), rs.getString("refund_to"), rs.getObject("requested_by", UUID.class), rs.getObject("approved_by", UUID.class),
                decided == null ? null : decided.toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("not serializable", e);
        }
    }

    <T> T fromJson(String s, TypeReference<T> type) {
        try {
            return json.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalStateException("stored json is unreadable", e);
        }
    }

    private List<TaxEngine.Component> readComponents(String s) {
        return fromJson(s, new TypeReference<List<TaxEngine.Component>>() { });
    }
}
