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
import static com.nabd.hms.billing.CheckoutModels.PharmacyChargeRow;
import static com.nabd.hms.billing.CheckoutModels.LineItemRow;
import static com.nabd.hms.billing.CheckoutModels.PaymentRow;
import static com.nabd.hms.billing.CheckoutModels.PrescribedItemRow;
import static com.nabd.hms.billing.CheckoutModels.QueueEntryContext;
import static com.nabd.hms.billing.CheckoutModels.VoidableInvoice;

@Repository
class CheckoutRepository {

    private final JdbcTemplate jdbc;

    CheckoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> findTenantRegion(UUID tenantId) {
        return jdbc.query("SELECT region_code FROM tenants WHERE id = ?", (rs, i) -> rs.getString("region_code"), tenantId)
                .stream().findFirst();
    }

    // Pharmacy items (E16, Hybrid mode) are charge_catalogue rows with a non-null stock_qty — hidden
    // from Fast Checkout entirely unless the tenant's pharmacy mode is 'hybrid', matching the
    // wireframe's "in External mode every inventory and dispensing screen is genuinely absent" rule.
    List<ChargeRow> listActiveCharges(UUID tenantId) {
        return jdbc.query(
                "SELECT c.id, c.code, c.name, c.category, c.base_amount, c.follow_up_amount, c.tax_rate_percent, c.stock_qty, " +
                        "COALESCE(c.is_rx, false) AS is_rx " +
                        "FROM charge_catalogue c LEFT JOIN pharmacy_settings ps ON ps.tenant_id = c.tenant_id " +
                        "WHERE c.tenant_id = ? AND c.active = true AND (c.stock_qty IS NULL OR ps.mode = 'hybrid') " +
                        "ORDER BY c.display_order, c.name",
                (rs, i) -> new ChargeRow(UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("name"),
                        rs.getString("category"), rs.getBigDecimal("base_amount"), rs.getBigDecimal("follow_up_amount"),
                        rs.getBigDecimal("tax_rate_percent"), (Integer) rs.getObject("stock_qty"), rs.getBoolean("is_rx")),
                tenantId);
    }

    /** NB-146: a completed-but-unbilled procedure the nurse finished during this visit — surfaced so
     * Fast Checkout can pre-load it, the same narrow-direct-query precedent as pharmacy stock (V25). */
    List<ChargeRow> listPendingProcedures(UUID tenantId, UUID queueEntryId) {
        return jdbc.query(
                "SELECT id, charge_code, charge_name, base_amount, tax_rate_percent FROM procedure_orders " +
                        "WHERE tenant_id = ? AND queue_entry_id = ? AND status = 'completed' AND billed = false",
                (rs, i) -> new ChargeRow(UUID.fromString(rs.getString("id")), rs.getString("charge_code"),
                        rs.getString("charge_name"), "Procedure", rs.getBigDecimal("base_amount"), null,
                        rs.getBigDecimal("tax_rate_percent"), null, false),
                tenantId, queueEntryId);
    }

    /** NB-179: the signed Rx for this visit, so whoever's billing can see what to dispense —
     * no separate dispense screen, same "visible from billing" precedent as pharmacy stock (V25). */
    List<PrescribedItemRow> findSignedPrescriptionItems(UUID tenantId, UUID queueEntryId) {
        return jdbc.query(
                "SELECT pi.id, pi.drug_name, pi.dosage, pi.frequency, pi.duration, pi.instructions, pi.quantity, " +
                        "(SELECT COALESCE(SUM(li.quantity), 0) FROM invoice_line_items li JOIN invoices i ON i.id = li.invoice_id " +
                        "  WHERE li.tenant_id = pi.tenant_id AND li.prescription_item_id = pi.id AND i.status <> 'void') AS dispensed " +
                        "FROM prescription_items pi JOIN prescriptions pr ON pr.id = pi.prescription_id " +
                        "WHERE pr.tenant_id = ? AND pr.queue_entry_id = ? AND pr.status = 'signed' ORDER BY pi.display_order",
                (rs, i) -> new PrescribedItemRow(UUID.fromString(rs.getString("id")), rs.getString("drug_name"), rs.getString("dosage"),
                        rs.getString("frequency"), rs.getString("duration"), rs.getString("instructions"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("dispensed")),
                tenantId, queueEntryId);
    }

    void markProceduresBilled(UUID tenantId, UUID queueEntryId) {
        jdbc.update("UPDATE procedure_orders SET billed = true WHERE tenant_id = ? AND queue_entry_id = ? " +
                "AND status = 'completed' AND billed = false", tenantId, queueEntryId);
    }

    // Not reaching into QueueRepository/PatientService (different packages) for these — same
    // narrow-direct-query precedent as CheckoutRepository.planExists() in the platform module.
    Optional<QueueEntryContext> findQueueEntryContext(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id, doctor_id, department_id, status, appointment_id FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new QueueEntryContext(UUID.fromString(rs.getString("patient_id")), UUID.fromString(rs.getString("doctor_id")),
                        UUID.fromString(rs.getString("department_id")), rs.getString("status"), rs.getString("appointment_id") != null),
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
        appendLineItems(tenantId, invoiceId, items, 0);
    }

    /** NB-355: a department whose flow bills mid-visit (e.g. a consultation fee at an interim
     * billing_pending stop) keeps adding to the SAME invoice at each later billing checkpoint
     * rather than creating a new one — display_order continues from whatever's already there. */
    void appendLineItems(UUID tenantId, UUID invoiceId, List<LineItemInput> items, int startOrder) {
        int order = startOrder;
        for (LineItemInput item : items) {
            decrementPharmacyStockIfApplicable(tenantId, invoiceId, item.chargeCode(), item.quantity());
            BigDecimal lineTotal = item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
            jdbc.update(
                    "INSERT INTO invoice_line_items (tenant_id, invoice_id, charge_code, charge_name, category, " +
                            "quantity, unit_price, tax_rate_percent, line_total, display_order, charge_catalogue_id, list_unit_price, " +
                            "tier, override_reason_code, override_note, overridden_by, is_rx, prescription_item_id) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, invoiceId, item.chargeCode(), item.chargeName(), item.category(), item.quantity(),
                    item.unitPrice(), item.taxRatePercent(), lineTotal, order++, item.catalogueId(), item.listUnitPrice(),
                    item.tier(), item.overrideReasonCode(), item.overrideNote(), item.overriddenBy(), item.isRx(), item.prescriptionItemId());
        }
    }

    int nextLineItemOrder(UUID tenantId, UUID invoiceId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(display_order), -1) FROM invoice_line_items WHERE tenant_id = ? AND invoice_id = ?",
                Integer.class, tenantId, invoiceId);
        return (max == null ? -1 : max) + 1;
    }

    /** Recomputes and overwrites an existing invoice's totals. FIN-003: {@code discount} is the SUM of the
     * invoice_discounts ledger (earlier checkpoints plus this one), never a replacement. */
    void updateInvoiceTotals(UUID tenantId, UUID invoiceId, BigDecimal subtotal, BigDecimal discount,
                              BigDecimal tax, BigDecimal roundOff, BigDecimal total, String status) {
        jdbc.update("UPDATE invoices SET subtotal = ?, discount = ?, tax = ?, round_off = ?, total = ?, status = ?, version = version + 1 " +
                        "WHERE tenant_id = ? AND id = ?",
                subtotal, discount, tax, roundOff, total, status, tenantId, invoiceId);
    }

    /** V3-004: the catalogue rows behind these codes, active or not, keyed by code; one batched query (PHL-001, DP-30). */
    java.util.Map<String, PharmacyChargeRow> findCatalogueCharges(UUID tenantId, List<String> chargeCodes) {
        java.util.Map<String, PharmacyChargeRow> out = new java.util.HashMap<>();
        jdbc.query(
                "SELECT code, id, name, category, base_amount, follow_up_amount, tax_rate_percent, active, doctor_override, " +
                        "stock_qty IS NOT NULL AS stocked, COALESCE(is_rx, false) AS is_rx FROM charge_catalogue " +
                        "WHERE tenant_id = ? AND code = ANY (?)",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString("code"), new PharmacyChargeRow(
                        UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("category"), rs.getBigDecimal("base_amount"),
                        rs.getBigDecimal("follow_up_amount"), rs.getBigDecimal("tax_rate_percent"), rs.getBoolean("active"),
                        rs.getBoolean("doctor_override"), rs.getBoolean("stocked"), rs.getBoolean("is_rx"))),
                tenantId, chargeCodes.toArray(String[]::new));
        return out;
    }

    void insertDiscounts(UUID tenantId, UUID invoiceId, List<BillingPricing.DiscountInput> discounts, UUID staffId) {
        for (var d : discounts) {
            jdbc.update("INSERT INTO invoice_discounts (tenant_id, invoice_id, kind, value, amount, reason_code, note, applied_by) " +
                    "VALUES (?,?,?,?,?,?,?,?)", tenantId, invoiceId, d.kind(), d.value(), d.amount(), d.reasonCode(), d.note(), staffId);
        }
    }

    BigDecimal sumDiscounts(UUID tenantId, UUID invoiceId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM invoice_discounts WHERE tenant_id = ? AND invoice_id = ?",
                BigDecimal.class, tenantId, invoiceId);
    }

    Optional<String> findPharmacyMode(UUID tenantId) {
        return jdbc.query("SELECT mode FROM pharmacy_settings WHERE tenant_id = ?",
                (rs, i) -> rs.getString("mode"), tenantId).stream().findFirst();
    }

    /** E16 Pharmacy: billing a pharmacy item (stock_qty IS NOT NULL) decrements stock in the same
     * transaction — "one invoice, one-tap stock deduction", the wireframe's own words. Non-pharmacy
     * charges (stock_qty IS NULL) are untouched. */
    private void decrementPharmacyStockIfApplicable(UUID tenantId, UUID invoiceId, String chargeCode, int quantity) {
        List<UUID> pharmacy = jdbc.query("SELECT id FROM charge_catalogue WHERE tenant_id = ? AND code = ? AND stock_qty IS NOT NULL",
                (rs, i) -> UUID.fromString(rs.getString("id")), tenantId, chargeCode);
        if (pharmacy.isEmpty()) {
            return;
        }
        // The guarded UPDATE takes the row lock, so it serialises with a manual stock adjustment (PharmacyService).
        List<Integer> after = jdbc.query(
                "UPDATE charge_catalogue SET stock_qty = stock_qty - ? WHERE tenant_id = ? AND code = ? AND stock_qty >= ? " +
                        "RETURNING stock_qty",
                (rs, i) -> rs.getInt("stock_qty"), quantity, tenantId, chargeCode, quantity);
        if (after.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Insufficient stock",
                    "Not enough stock for " + chargeCode + " to dispense " + quantity + " unit(s).");
        }
        // Immutable movement row (V51): every stock change, including a checkout dispense, leaves one.
        if (quantity > 0) {
            jdbc.update("INSERT INTO pharmacy_stock_movements (tenant_id, item_id, kind, delta, qty_before, qty_after, reason, invoice_id) " +
                            "VALUES (?,?,'dispense',?,?,?,?,?)",
                    tenantId, pharmacy.get(0), -quantity, after.get(0) + quantity, after.get(0), "Dispensed at checkout", invoiceId);
        }
    }

    /** PHA-011: locks the invoice row so two concurrent voids (or a void racing a payment) run one after the other. */
    Optional<VoidableInvoice> lockInvoiceForVoid(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT invoice_number, status, paid FROM invoices WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, i) -> new VoidableInvoice(rs.getString("invoice_number"), rs.getString("status"), rs.getBigDecimal("paid")),
                tenantId, invoiceId).stream().findFirst();
    }

    void markInvoiceVoid(UUID tenantId, UUID invoiceId, UUID staffId, String reason) {
        jdbc.update("UPDATE invoices SET status = 'void', voided_at = now(), voided_by = ?, void_reason = ? " +
                "WHERE tenant_id = ? AND id = ?", staffId, reason, tenantId, invoiceId);
    }

    /** PHA-011: puts every stocked line of the invoice back on the shelf, one immutable 'restock' movement per
     * line. Returns {code, quantity} pairs restored, for the audit detail. Caller holds the invoice lock and
     * the transaction, so the restock and the status change commit or roll back together. */
    List<Object[]> restockInvoiceLines(UUID tenantId, UUID invoiceId, UUID staffId, String reason) {
        List<Object[]> restored = new java.util.ArrayList<>();
        // Sum per code so a code billed on two lines (two checkpoints) restocks in one movement; ORDER BY keeps lock order stable.
        List<Object[]> lines = jdbc.query(
                "SELECT c.id, li.charge_code, SUM(li.quantity) AS qty FROM invoice_line_items li " +
                        "JOIN charge_catalogue c ON c.tenant_id = li.tenant_id AND c.code = li.charge_code AND c.stock_qty IS NOT NULL " +
                        "WHERE li.tenant_id = ? AND li.invoice_id = ? GROUP BY c.id, li.charge_code ORDER BY li.charge_code",
                (rs, i) -> new Object[]{UUID.fromString(rs.getString("id")), rs.getString("charge_code"), rs.getInt("qty")},
                tenantId, invoiceId);
        for (Object[] l : lines) {
            int qty = (Integer) l[2];
            Integer after = jdbc.queryForObject(
                    "UPDATE charge_catalogue SET stock_qty = stock_qty + ?, updated_at = now() WHERE tenant_id = ? AND id = ? RETURNING stock_qty",
                    Integer.class, qty, tenantId, l[0]);
            jdbc.update("INSERT INTO pharmacy_stock_movements (tenant_id, item_id, kind, delta, qty_before, qty_after, reason, invoice_id, actor_id) " +
                            "VALUES (?,?,'restock',?,?,?,?,?,?)",
                    tenantId, l[0], qty, after - qty, after, reason, invoiceId, staffId);
            restored.add(new Object[]{l[1], qty});
        }
        return restored;
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
                "SELECT charge_code, charge_name, category, quantity, unit_price, tax_rate_percent, line_total, " +
                        "list_unit_price, tier, override_reason_code FROM invoice_line_items WHERE tenant_id = ? AND invoice_id = ? ORDER BY display_order",
                (rs, i) -> new LineItemRow(rs.getString("charge_code"), rs.getString("charge_name"), rs.getString("category"),
                        rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("tax_rate_percent"),
                        rs.getBigDecimal("line_total"), rs.getBigDecimal("list_unit_price"), rs.getString("tier"),
                        rs.getString("override_reason_code")),
                tenantId, invoiceId);
    }

    List<PaymentRow> findPayments(UUID tenantId, UUID invoiceId) {
        return jdbc.query(
                "SELECT id, method, amount, recorded_at, idempotency_key FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ? ORDER BY recorded_at",
                (rs, i) -> new PaymentRow(UUID.fromString(rs.getString("id")), rs.getString("method"),
                        rs.getBigDecimal("amount"), rs.getTimestamp("recorded_at").toInstant(), rs.getString("idempotency_key")),
                tenantId, invoiceId);
    }

    /** FIN-001: the invoice row lock that serialises payments, totals changes and voids on one invoice. */
    Optional<InvoiceRow> lockInvoice(UUID tenantId, UUID invoiceId) {
        return jdbc.query(INVOICE_SELECT + "WHERE tenant_id = ? AND id = ? FOR UPDATE", invoiceMapper(), tenantId, invoiceId)
                .stream().findFirst();
    }

    /** The invoice a payment with this Idempotency-Key was already recorded on, if any. */
    Optional<UUID> findInvoiceByPaymentKey(UUID tenantId, String key) {
        return jdbc.query("SELECT invoice_id FROM invoice_payments WHERE tenant_id = ? AND idempotency_key = ?",
                (rs, i) -> UUID.fromString(rs.getString("invoice_id")), tenantId, key).stream().findFirst();
    }

    UUID insertPayment(UUID tenantId, UUID invoiceId, String method, BigDecimal amount, UUID recordedBy,
                       String reference, String idempotencyKey) {
        return jdbc.queryForObject("INSERT INTO invoice_payments (tenant_id, invoice_id, method, amount, recorded_by, reference, idempotency_key) " +
                "VALUES (?,?,?,?,?,?,?) RETURNING id", UUID.class,
                tenantId, invoiceId, method, amount, recordedBy, reference, idempotencyKey);
    }

    /** paid is always the sum of the payment rows, never an absolute value computed in Java. */
    void syncInvoicePaid(UUID tenantId, UUID invoiceId, String status) {
        jdbc.update("UPDATE invoices SET paid = (SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ?), " +
                        "status = ?, version = version + 1 WHERE tenant_id = ? AND id = ?",
                tenantId, invoiceId, status, tenantId, invoiceId);
    }

    BigDecimal sumPayments(UUID tenantId, UUID invoiceId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ?",
                BigDecimal.class, tenantId, invoiceId);
    }

    private static final String INVOICE_SELECT =
            "SELECT id, invoice_number, queue_entry_id, patient_id, doctor_id, subtotal, discount, tax, round_off, " +
                    "total, paid, status, created_at FROM invoices ";

    private RowMapper<InvoiceRow> invoiceMapper() {
        return (rs, i) -> new InvoiceRow(
                UUID.fromString(rs.getString("id")), rs.getString("invoice_number"),
                uuidOrNull(rs, "queue_entry_id"), uuidOrNull(rs, "patient_id"),
                uuidOrNull(rs, "doctor_id"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount"),
                rs.getBigDecimal("tax"), rs.getBigDecimal("round_off"), rs.getBigDecimal("total"), rs.getBigDecimal("paid"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }

    /** NB-186: OTC sale invoices leave queue_entry_id/patient_id/doctor_id null. */
    private static UUID uuidOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String s = rs.getString(column);
        return s == null ? null : UUID.fromString(s);
    }
}
