package com.nabd.hms.billing;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.billing.CheckoutModels.ChargeRow;
import static com.nabd.hms.billing.CheckoutModels.InvoiceRow;
import static com.nabd.hms.billing.CheckoutModels.LineItemInput;
import static com.nabd.hms.billing.CheckoutModels.LineItemRow;
import static com.nabd.hms.billing.CheckoutModels.PaymentRow;
import static com.nabd.hms.billing.CheckoutModels.QueueEntryContext;

@Repository
class CheckoutRepository {

    private final JdbcTemplate jdbc;

    CheckoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> findTenantRegion(UUID tenantId) {
        return jdbc.query("SELECT region FROM tenants WHERE id = ?", (rs, i) -> rs.getString("region"), tenantId)
                .stream().findFirst();
    }

    // Pharmacy items (E16, Hybrid mode) are charge_catalogue rows with a non-null stock_qty — hidden
    // from Fast Checkout entirely unless the tenant's pharmacy mode is 'hybrid', matching the
    // wireframe's "in External mode every inventory and dispensing screen is genuinely absent" rule.
    List<ChargeRow> listActiveCharges(UUID tenantId) {
        return jdbc.query(
                "SELECT c.id, c.code, c.name, c.category, c.base_amount, c.follow_up_amount, c.tax_rate_percent " +
                        "FROM charge_catalogue c LEFT JOIN pharmacy_settings ps ON ps.tenant_id = c.tenant_id " +
                        "WHERE c.tenant_id = ? AND c.active = true AND (c.stock_qty IS NULL OR ps.mode = 'hybrid') " +
                        "ORDER BY c.display_order, c.name",
                (rs, i) -> new ChargeRow(UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("name"),
                        rs.getString("category"), rs.getBigDecimal("base_amount"), rs.getBigDecimal("follow_up_amount"),
                        rs.getBigDecimal("tax_rate_percent")),
                tenantId);
    }

    // Not reaching into QueueRepository/PatientService (different packages) for these — same
    // narrow-direct-query precedent as CheckoutRepository.planExists() in the platform module.
    Optional<QueueEntryContext> findQueueEntryContext(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id, doctor_id, status, appointment_id FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new QueueEntryContext(UUID.fromString(rs.getString("patient_id")), UUID.fromString(rs.getString("doctor_id")),
                        rs.getString("status"), rs.getString("appointment_id") != null),
                tenantId, queueEntryId).stream().findFirst();
    }

    Optional<String> findPatientName(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT name FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString("name"), tenantId, patientId).stream().findFirst();
    }

    Optional<String> findStaffName(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT name FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString("name"), tenantId, staffId).stream().findFirst();
    }

    /** NB-162's follow-up pricing auto-detection: same doctor, a completed visit within the window. */
    boolean hasRecentCompletedVisit(UUID tenantId, UUID patientId, UUID doctorId, LocalDate since) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM queue_entries WHERE tenant_id = ? AND patient_id = ? AND doctor_id = ? " +
                        "AND status = 'completed' AND queue_date >= ?)",
                Boolean.class, tenantId, patientId, doctorId, Date.valueOf(since));
        return Boolean.TRUE.equals(exists);
    }

    /** invoice_number is DB-generated (DEFAULT nextval), so insert then read back rather than compute it here. */
    UUID insertInvoice(UUID tenantId, UUID queueEntryId, UUID patientId, UUID doctorId, BigDecimal subtotal,
                        BigDecimal discount, BigDecimal tax, BigDecimal roundOff, BigDecimal total, UUID createdBy) {
        return jdbc.queryForObject(
                "INSERT INTO invoices (tenant_id, queue_entry_id, patient_id, doctor_id, subtotal, discount, tax, round_off, total, created_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, queueEntryId, patientId, doctorId, subtotal, discount, tax, roundOff, total, createdBy);
    }

    void insertLineItems(UUID tenantId, UUID invoiceId, List<LineItemInput> items) {
        int order = 0;
        for (LineItemInput item : items) {
            decrementPharmacyStockIfApplicable(tenantId, item.chargeCode(), item.quantity());
            BigDecimal lineTotal = item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
            jdbc.update(
                    "INSERT INTO invoice_line_items (tenant_id, invoice_id, charge_code, charge_name, category, " +
                            "quantity, unit_price, tax_rate_percent, line_total, display_order) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    tenantId, invoiceId, item.chargeCode(), item.chargeName(), item.category(), item.quantity(),
                    item.unitPrice(), item.taxRatePercent(), lineTotal, order++);
        }
    }

    /** E16 Pharmacy: billing a pharmacy item (stock_qty IS NOT NULL) decrements stock in the same
     * transaction — "one invoice, one-tap stock deduction", the wireframe's own words. Non-pharmacy
     * charges (stock_qty IS NULL) are untouched. */
    private void decrementPharmacyStockIfApplicable(UUID tenantId, String chargeCode, int quantity) {
        List<Integer> stock = jdbc.query("SELECT stock_qty FROM charge_catalogue WHERE tenant_id = ? AND code = ?",
                (rs, i) -> (Integer) rs.getObject("stock_qty"), tenantId, chargeCode);
        if (stock.isEmpty() || stock.get(0) == null) {
            return;
        }
        int updated = jdbc.update(
                "UPDATE charge_catalogue SET stock_qty = stock_qty - ? WHERE tenant_id = ? AND code = ? AND stock_qty >= ?",
                quantity, tenantId, chargeCode, quantity);
        if (updated == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Insufficient stock",
                    "Not enough stock for " + chargeCode + " to dispense " + quantity + " unit(s).");
        }
    }

    Optional<InvoiceRow> findInvoice(UUID tenantId, UUID invoiceId) {
        return jdbc.query(INVOICE_SELECT + "WHERE tenant_id = ? AND id = ?", invoiceMapper(), tenantId, invoiceId)
                .stream().findFirst();
    }

    Optional<InvoiceRow> findInvoiceByQueueEntry(UUID tenantId, UUID queueEntryId) {
        return jdbc.query(INVOICE_SELECT + "WHERE tenant_id = ? AND queue_entry_id = ?", invoiceMapper(), tenantId, queueEntryId)
                .stream().findFirst();
    }

    List<LineItemRow> findLineItems(UUID tenantId, UUID invoiceId) {
        return jdbc.query(
                "SELECT charge_code, charge_name, category, quantity, unit_price, tax_rate_percent, line_total " +
                        "FROM invoice_line_items WHERE tenant_id = ? AND invoice_id = ? ORDER BY display_order",
                (rs, i) -> new LineItemRow(rs.getString("charge_code"), rs.getString("charge_name"), rs.getString("category"),
                        rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("tax_rate_percent"),
                        rs.getBigDecimal("line_total")),
                tenantId, invoiceId);
    }

    List<PaymentRow> findPayments(UUID tenantId, UUID invoiceId) {
        return jdbc.query(
                "SELECT id, method, amount, recorded_at FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ? ORDER BY recorded_at",
                (rs, i) -> new PaymentRow(UUID.fromString(rs.getString("id")), rs.getString("method"),
                        rs.getBigDecimal("amount"), rs.getTimestamp("recorded_at").toInstant()),
                tenantId, invoiceId);
    }

    void insertPayment(UUID tenantId, UUID invoiceId, String method, BigDecimal amount, UUID recordedBy) {
        jdbc.update("INSERT INTO invoice_payments (tenant_id, invoice_id, method, amount, recorded_by) VALUES (?,?,?,?,?)",
                tenantId, invoiceId, method, amount, recordedBy);
    }

    void updateInvoicePaidStatus(UUID tenantId, UUID invoiceId, BigDecimal paid, String status) {
        jdbc.update("UPDATE invoices SET paid = ?, status = ? WHERE tenant_id = ? AND id = ?",
                paid, status, tenantId, invoiceId);
    }

    private static final String INVOICE_SELECT =
            "SELECT id, invoice_number, queue_entry_id, patient_id, doctor_id, subtotal, discount, tax, round_off, " +
                    "total, paid, status, created_at FROM invoices ";

    private RowMapper<InvoiceRow> invoiceMapper() {
        return (rs, i) -> new InvoiceRow(
                UUID.fromString(rs.getString("id")), rs.getString("invoice_number"),
                UUID.fromString(rs.getString("queue_entry_id")), UUID.fromString(rs.getString("patient_id")),
                UUID.fromString(rs.getString("doctor_id")), rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount"),
                rs.getBigDecimal("tax"), rs.getBigDecimal("round_off"), rs.getBigDecimal("total"), rs.getBigDecimal("paid"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }
}
