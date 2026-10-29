package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ClinicClock;
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
import static com.nabd.hms.pharmacy.PharmacyModels.RuleRow;
import static com.nabd.hms.pharmacy.PharmacyModels.SettingsRow;
import static com.nabd.hms.pharmacy.PharmacyModels.StockMovementRow;

/** Pharmacy items are charge_catalogue rows tagged with a non-null stock_qty — no parallel item
 * table, so a dispensed item is automatically a billable Fast Checkout charge (see
 * CheckoutRepository.listActiveCharges/decrementPharmacyStockIfApplicable for the other side). */
@Repository
class PharmacyRepository {

    private static final String CATEGORY = "Pharmacy";

    private final JdbcTemplate jdbc;

    private final ClinicClock clock;

    PharmacyRepository(JdbcTemplate jdbc, ClinicClock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
    }

    Optional<String> findMode(UUID tenantId) {
        return jdbc.query("SELECT mode FROM pharmacy_settings WHERE tenant_id = ?",
                (rs, i) -> rs.getString("mode"), tenantId).stream().findFirst();
    }

    Optional<SettingsRow> findSettings(UUID tenantId) {
        return jdbc.query("SELECT mode, mode_changed_at, drug_licence_numbers::text AS lic, pharmacist_in_charge_staff_id, " +
                        "alert_recipient_staff_id FROM pharmacy_settings WHERE tenant_id = ?",
                (rs, i) -> new SettingsRow(rs.getString("mode"),
                        rs.getTimestamp("mode_changed_at") == null ? null : rs.getTimestamp("mode_changed_at").toInstant(),
                        rs.getString("lic"), uuid(rs.getString("pharmacist_in_charge_staff_id")),
                        uuid(rs.getString("alert_recipient_staff_id"))), tenantId).stream().findFirst();
    }

    /** PHL-004: writes the whole settings row; mode_changed_* move only when the mode really changed. */
    void saveSettings(UUID tenantId, String mode, boolean modeChanged, UUID actorId, String licenceJson, UUID inCharge, UUID alertTo) {
        jdbc.update("INSERT INTO pharmacy_settings (tenant_id, mode, mode_changed_at, mode_changed_by, drug_licence_numbers, " +
                        "pharmacist_in_charge_staff_id, alert_recipient_staff_id) " +
                        "VALUES (?, ?, CASE WHEN ? THEN now() END, CASE WHEN ? THEN ?::uuid END, ?::jsonb, ?, ?) " +
                        "ON CONFLICT (tenant_id) DO UPDATE SET mode = EXCLUDED.mode, " +
                        "mode_changed_at = COALESCE(EXCLUDED.mode_changed_at, pharmacy_settings.mode_changed_at), " +
                        "mode_changed_by = COALESCE(EXCLUDED.mode_changed_by, pharmacy_settings.mode_changed_by), " +
                        "drug_licence_numbers = EXCLUDED.drug_licence_numbers, " +
                        "pharmacist_in_charge_staff_id = EXCLUDED.pharmacist_in_charge_staff_id, " +
                        "alert_recipient_staff_id = EXCLUDED.alert_recipient_staff_id, updated_at = now()",
                tenantId, mode, modeChanged, modeChanged, actorId == null ? null : actorId.toString(), licenceJson, inCharge, alertTo);
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static final String ITEM_SELECT =
            "SELECT c.id, c.code, c.name, c.is_rx, c.hsn_code, c.base_amount, c.tax_rate_percent, c.stock_qty, c.active, c.drug_id, " +
                    "COALESCE(p.schedule_class, 'OTC') AS schedule_class, " +
                    "(SELECT max(r.register_type) FROM pharmacy_schedule_rules r WHERE r.schedule_class = p.schedule_class) AS register_type, " +
                    "p.generic_name, p.pack_size, p.unit, COALESCE(p.in_house, true) AS in_house, " +
                    "p.price_inclusive_of_tax, COALESCE(p.zero_rated, false) AS zero_rated, p.mrp " +
                    "FROM charge_catalogue c LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id ";

    /** @param scheduleClass optional PHL-002 filter (index on tenant_id, schedule_class) */
    List<PharmacyItemRow> listItems(UUID tenantId, String scheduleClass) {
        return jdbc.query(ITEM_SELECT + "WHERE c.tenant_id = ? AND c.stock_qty IS NOT NULL AND (?::text IS NULL OR p.schedule_class = ?) ORDER BY c.name",
                itemMapper(), tenantId, scheduleClass, scheduleClass);
    }

    Optional<PharmacyItemRow> findItem(UUID tenantId, UUID id) {
        return jdbc.query(ITEM_SELECT + "WHERE c.tenant_id = ? AND c.id = ? AND c.stock_qty IS NOT NULL",
                itemMapper(), tenantId, id).stream().findFirst();
    }

    /** V2-004: row lock held to the end of the transaction, so a manual adjustment and a checkout dispense
     * on the same item run one after the other and each sees the other's result. */
    Optional<PharmacyItemRow> lockItem(UUID tenantId, UUID id) {
        return jdbc.query(ITEM_SELECT + "WHERE c.tenant_id = ? AND c.id = ? AND c.stock_qty IS NOT NULL FOR UPDATE OF c",
                itemMapper(), tenantId, id).stream().findFirst();
    }

    /** PHL-003: the one stock write. Guarded, so the balance never goes below zero; empty = no such item or not enough. */
    Optional<Integer> applyDelta(UUID tenantId, UUID id, int delta) {
        return jdbc.query("UPDATE charge_catalogue SET stock_qty = stock_qty + ?, updated_at = now() " +
                        "WHERE tenant_id = ? AND id = ? AND stock_qty IS NOT NULL AND stock_qty + ? >= 0 RETURNING stock_qty",
                (rs, i) -> rs.getInt("stock_qty"), delta, tenantId, id, delta).stream().findFirst();
    }

    /** PHL-010: the stock-take form of {@link #lockItem}: all rows in one statement, locked in id order (no deadlock between two
     * multi-item lockers). Item id -> cached stock_qty; unknown or non-stocked items are absent. */
    java.util.Map<UUID, Integer> lockItems(UUID tenantId, UUID[] ids) {
        java.util.Map<UUID, Integer> out = new java.util.LinkedHashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, stock_qty FROM charge_catalogue WHERE tenant_id = ? AND id = ANY (?) " +
                    "AND stock_qty IS NOT NULL ORDER BY id FOR UPDATE");
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", ids));
            return ps;
        }, rs -> {
            out.put(rs.getObject(1, UUID.class), rs.getInt(2));
        });
        return out;
    }

    /** PHL-010: set-based {@link #applyDelta}; guarded, so the number of rows changed is less than ids.length when one would go negative. */
    int applyDeltas(UUID tenantId, UUID[] ids, Integer[] deltas) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE charge_catalogue c SET stock_qty = c.stock_qty + d.delta, updated_at = now() " +
                    "FROM unnest(?::uuid[], ?::int[]) AS d(id, delta) WHERE c.tenant_id = ? AND c.id = d.id AND c.stock_qty IS NOT NULL " +
                    "AND c.stock_qty + d.delta >= 0");
            ps.setArray(1, con.createArrayOf("uuid", ids));
            ps.setArray(2, con.createArrayOf("int4", deltas));
            ps.setObject(3, tenantId);
            return ps;
        });
    }

    Optional<UUID> findStockedItemByCode(UUID tenantId, String code) {
        return jdbc.query("SELECT id FROM charge_catalogue WHERE tenant_id = ? AND code = ? AND stock_qty IS NOT NULL",
                (rs, i) -> UUID.fromString(rs.getString("id")), tenantId, code).stream().findFirst();
    }

    Optional<String> scheduleClassOf(UUID tenantId, UUID itemId) {
        return jdbc.query("SELECT schedule_class FROM pharmacy_item_profiles WHERE tenant_id = ? AND item_id = ?",
                (rs, i) -> rs.getString("schedule_class"), tenantId, itemId).stream().findFirst();
    }

    /** PHL-002: class and descriptive fields; a null generic/pack/unit/inHouse leaves the stored value. */
    void upsertProfile(UUID tenantId, UUID itemId, String scheduleClass, String genericName, Integer packSize, String unit, Boolean inHouse) {
        jdbc.update("INSERT INTO pharmacy_item_profiles (item_id, tenant_id, schedule_class, generic_name, pack_size, unit, in_house) " +
                        "VALUES (?,?,?,?,?,?,COALESCE(?, true)) ON CONFLICT (item_id) DO UPDATE SET schedule_class = EXCLUDED.schedule_class, " +
                        "generic_name = COALESCE(?, pharmacy_item_profiles.generic_name), pack_size = COALESCE(?, pharmacy_item_profiles.pack_size), " +
                        "unit = COALESCE(?, pharmacy_item_profiles.unit), in_house = COALESCE(?, pharmacy_item_profiles.in_house)",
                itemId, tenantId, scheduleClass, genericName, packSize, unit, inHouse, genericName, packSize, unit, inHouse);
    }

    List<RuleRow> rulesForMarket(String market) {
        return jdbc.query("SELECT schedule_class, label, requires_rx, register_type, duplicate_rx_copy, retention_years, " +
                        "refill_self_serve_allowed, drug_master_flag FROM pharmacy_schedule_rules WHERE market = ? ORDER BY schedule_class <> 'OTC', schedule_class",
                (rs, i) -> new RuleRow(rs.getString("schedule_class"), rs.getString("label"), rs.getBoolean("requires_rx"),
                        rs.getString("register_type"), rs.getBoolean("duplicate_rx_copy"), (Integer) rs.getObject("retention_years"),
                        rs.getBoolean("refill_self_serve_allowed"), rs.getString("drug_master_flag")), market);
    }

    /** PHL-004: lower-cased names of active items this clinic does not dispense (flagged in_house = false). */
    List<String> buyOutsideNames(UUID tenantId) {
        return jdbc.queryForList("SELECT lower(btrim(c.name)) FROM charge_catalogue c JOIN pharmacy_item_profiles p " +
                "ON p.item_id = c.id AND p.tenant_id = c.tenant_id WHERE c.tenant_id = ? AND c.active AND NOT p.in_house ORDER BY 1",
                String.class, tenantId);
    }

    String tenantMarket(UUID tenantId) {
        return jdbc.queryForObject("SELECT trim(region_code) FROM tenants WHERE id = ?", String.class, tenantId);
    }

    void setActive(UUID tenantId, UUID id, boolean active) {
        patchItem(tenantId, id, active, null);
    }

    /** CLN-019: map an item to a drug master product; pricing and stock are untouched. */
    void setDrug(UUID tenantId, UUID id, String drugId) {
        patchItem(tenantId, id, null, drugId);
    }

    /** One statement for the two single-column item flags (a null argument leaves its column as it is). */
    private void patchItem(UUID tenantId, UUID id, Boolean active, String drugId) {
        jdbc.update("UPDATE charge_catalogue SET active = COALESCE(?, active), drug_id = COALESCE(?, drug_id), updated_at = now() " +
                "WHERE tenant_id = ? AND id = ?", active, drugId, tenantId, id);
    }

    List<StockMovementRow> listMovements(UUID tenantId, UUID itemId) {
        return jdbc.query(
                "SELECT m.id, m.kind, m.delta, m.qty_before, m.qty_after, m.reason, m.actor_id, s.name AS actor_name, m.occurred_at " +
                        "FROM pharmacy_stock_movements m LEFT JOIN staff s ON s.id = m.actor_id " +
                        "WHERE m.tenant_id = ? AND m.item_id = ? ORDER BY m.seq DESC LIMIT 200",
                (rs, i) -> new StockMovementRow(UUID.fromString(rs.getString("id")), rs.getString("kind"), rs.getInt("delta"),
                        rs.getInt("qty_before"), rs.getInt("qty_after"), rs.getString("reason"),
                        rs.getString("actor_id") == null ? null : UUID.fromString(rs.getString("actor_id")),
                        rs.getString("actor_name"), rs.getTimestamp("occurred_at").toInstant()),
                tenantId, itemId);
    }

    UUID insertItem(UUID tenantId, String code, PharmacyItemWriteRequest req) {
        UUID id = UUID.randomUUID();
        // stock_qty starts at 0: opening stock goes through StockCommands (movement row + audit). is_rx is set by the profile trigger.
        jdbc.update(
                "INSERT INTO charge_catalogue (id, tenant_id, code, name, category, base_amount, tax_rate_percent, " +
                        "is_rx, hsn_code, stock_qty, active, effective_from) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, code, req.name(), CATEGORY, req.price(), req.taxRatePercentOrZero(),
                req.isRx(), req.hsnCode(), 0, true, java.sql.Date.valueOf(clock.today(tenantId)));
        return id;
    }

    /** Name, price, tax and HSN only. Stock is never written here (V2-004); is_rx follows the schedule class (PHL-002). */
    int updateItem(UUID tenantId, UUID id, PharmacyItemWriteRequest req) {
        return jdbc.update(
                "UPDATE charge_catalogue SET name = ?, base_amount = ?, tax_rate_percent = ?, " +
                        "hsn_code = ?, updated_at = now() " +
                        "WHERE tenant_id = ? AND id = ? AND stock_qty IS NOT NULL",
                req.name(), req.price(), req.taxRatePercentOrZero(), req.hsnCode(),
                tenantId, id);
    }

    /** NB-179: signed prescriptions still ahead of checkout, cross-patient — narrow direct query
     * into the clinical schema, same precedent as CheckoutRepository reading procedure_orders. */
    List<DispensingQueueRow> listDispensingQueue(UUID tenantId) {
        return jdbc.query(
                "SELECT pr.id AS prescription_id, pr.queue_entry_id, pr.patient_id, p.name AS patient_name, " +
                        "s.name AS doctor_name, pr.signed_at, q.token_number " +
                        "FROM prescriptions pr " +
                        "JOIN patients p ON p.id = pr.patient_id " +
                        "JOIN staff s ON s.id = pr.doctor_id " +
                        "JOIN queue_entries q ON q.id = pr.queue_entry_id " +
                        "WHERE pr.tenant_id = ? AND pr.status = 'signed' AND q.status != 'completed' " +
                        "ORDER BY pr.signed_at",
                (rs, i) -> new DispensingQueueRow(UUID.fromString(rs.getString("prescription_id")),
                        UUID.fromString(rs.getString("queue_entry_id")), UUID.fromString(rs.getString("patient_id")),
                        rs.getString("patient_name"), rs.getString("doctor_name"), rs.getTimestamp("signed_at").toInstant(), rs.getInt("token_number")),
                tenantId);
    }

    List<DispensingItemRow> listDispensingItems(UUID tenantId, UUID prescriptionId) {
        return jdbc.query(
                // PHA-003: prescription items carry no catalogue link, so stock is matched on the drug name
                // (exact, case-insensitive) against a stocked catalogue item; null = not stock-tracked / no match.
                "SELECT pi.drug_name, pi.dosage, pi.frequency, pi.duration, pi.instructions, " +
                        "(SELECT c.stock_qty FROM charge_catalogue c WHERE c.tenant_id = pi.tenant_id AND c.stock_qty IS NOT NULL " +
                        "AND c.active = true AND lower(btrim(c.name)) = lower(btrim(pi.drug_name)) ORDER BY c.code LIMIT 1) AS stock_qty " +
                        "FROM prescription_items pi WHERE pi.tenant_id = ? AND pi.prescription_id = ? ORDER BY pi.display_order",
                (rs, i) -> new DispensingItemRow(rs.getString("drug_name"), rs.getString("dosage"),
                        rs.getString("frequency"), rs.getString("duration"), rs.getString("instructions"),
                        (Integer) rs.getObject("stock_qty")),
                tenantId, prescriptionId);
    }

    private RowMapper<PharmacyItemRow> itemMapper() {
        return (rs, i) -> new PharmacyItemRow(UUID.fromString(rs.getString("id")), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("is_rx"), rs.getString("hsn_code"), rs.getBigDecimal("base_amount"),
                rs.getBigDecimal("tax_rate_percent"), rs.getInt("stock_qty"), rs.getBoolean("active"), rs.getString("drug_id"),
                rs.getString("schedule_class"), rs.getString("register_type"), rs.getString("generic_name"),
                (Integer) rs.getObject("pack_size"), rs.getString("unit"), rs.getBoolean("in_house"),
                (Boolean) rs.getObject("price_inclusive_of_tax"), rs.getBoolean("zero_rated"), rs.getBigDecimal("mrp"));
    }
}
