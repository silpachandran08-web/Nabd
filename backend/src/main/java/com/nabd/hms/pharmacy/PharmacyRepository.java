package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.PharmacyItemWriteRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.pharmacy.PharmacyModels.DispensingItemRow;
import static com.nabd.hms.pharmacy.PharmacyModels.DispensingQueueRow;
import static com.nabd.hms.pharmacy.PharmacyModels.PharmacyItemRow;

/** Pharmacy items are charge_catalogue rows tagged with a non-null stock_qty — no parallel item
 * table, so a dispensed item is automatically a billable Fast Checkout charge (see
 * CheckoutRepository.listActiveCharges/decrementPharmacyStockIfApplicable for the other side). */
@Repository
class PharmacyRepository {

    private static final String CATEGORY = "Pharmacy";

    private final JdbcTemplate jdbc;

    PharmacyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> findMode(UUID tenantId) {
        return jdbc.query("SELECT mode FROM pharmacy_settings WHERE tenant_id = ?",
                (rs, i) -> rs.getString("mode"), tenantId).stream().findFirst();
    }

    void upsertMode(UUID tenantId, String mode) {
        jdbc.update("INSERT INTO pharmacy_settings (tenant_id, mode) VALUES (?, ?) " +
                        "ON CONFLICT (tenant_id) DO UPDATE SET mode = EXCLUDED.mode, updated_at = now()",
                tenantId, mode);
    }

    List<PharmacyItemRow> listItems(UUID tenantId) {
        return jdbc.query(
                "SELECT id, code, name, is_rx, hsn_code, base_amount, tax_rate_percent, stock_qty, active " +
                        "FROM charge_catalogue WHERE tenant_id = ? AND stock_qty IS NOT NULL ORDER BY name",
                itemMapper(), tenantId);
    }

    Optional<PharmacyItemRow> findItem(UUID tenantId, UUID id) {
        return jdbc.query(
                "SELECT id, code, name, is_rx, hsn_code, base_amount, tax_rate_percent, stock_qty, active " +
                        "FROM charge_catalogue WHERE tenant_id = ? AND id = ? AND stock_qty IS NOT NULL",
                itemMapper(), tenantId, id).stream().findFirst();
    }

    UUID insertItem(UUID tenantId, String code, PharmacyItemWriteRequest req) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO charge_catalogue (id, tenant_id, code, name, category, base_amount, tax_rate_percent, " +
                        "is_rx, hsn_code, stock_qty, active, effective_from) VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_DATE)",
                id, tenantId, code, req.name(), CATEGORY, req.price(), req.taxRatePercentOrZero(),
                req.isRx(), req.hsnCode(), req.stockQtyOrZero(), true);
        return id;
    }

    int updateItem(UUID tenantId, UUID id, PharmacyItemWriteRequest req) {
        return jdbc.update(
                "UPDATE charge_catalogue SET name = ?, base_amount = ?, tax_rate_percent = ?, is_rx = ?, " +
                        "hsn_code = ?, stock_qty = ?, updated_at = now() " +
                        "WHERE tenant_id = ? AND id = ? AND stock_qty IS NOT NULL",
                req.name(), req.price(), req.taxRatePercentOrZero(), req.isRx(), req.hsnCode(), req.stockQtyOrZero(),
                tenantId, id);
    }

    /** NB-179: signed prescriptions still ahead of checkout, cross-patient — narrow direct query
     * into the clinical schema, same precedent as CheckoutRepository reading procedure_orders. */
    List<DispensingQueueRow> listDispensingQueue(UUID tenantId) {
        return jdbc.query(
                "SELECT pr.id AS prescription_id, pr.queue_entry_id, pr.patient_id, p.name AS patient_name, " +
                        "s.name AS doctor_name, pr.signed_at " +
                        "FROM prescriptions pr " +
                        "JOIN patients p ON p.id = pr.patient_id " +
                        "JOIN staff s ON s.id = pr.doctor_id " +
                        "JOIN queue_entries q ON q.id = pr.queue_entry_id " +
                        "WHERE pr.tenant_id = ? AND pr.status = 'signed' AND q.status != 'completed' " +
                        "ORDER BY pr.signed_at",
                (rs, i) -> new DispensingQueueRow(UUID.fromString(rs.getString("prescription_id")),
                        UUID.fromString(rs.getString("queue_entry_id")), UUID.fromString(rs.getString("patient_id")),
                        rs.getString("patient_name"), rs.getString("doctor_name"), rs.getTimestamp("signed_at").toInstant()),
                tenantId);
    }

    List<DispensingItemRow> listDispensingItems(UUID tenantId, UUID prescriptionId) {
        return jdbc.query(
                "SELECT drug_name, dosage, frequency, duration, instructions FROM prescription_items " +
                        "WHERE tenant_id = ? AND prescription_id = ? ORDER BY display_order",
                (rs, i) -> new DispensingItemRow(rs.getString("drug_name"), rs.getString("dosage"),
                        rs.getString("frequency"), rs.getString("duration"), rs.getString("instructions")),
                tenantId, prescriptionId);
    }

    private RowMapper<PharmacyItemRow> itemMapper() {
        return (rs, i) -> new PharmacyItemRow(UUID.fromString(rs.getString("id")), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("is_rx"), rs.getString("hsn_code"), rs.getBigDecimal("base_amount"),
                rs.getBigDecimal("tax_rate_percent"), rs.getInt("stock_qty"), rs.getBoolean("active"));
    }
}
