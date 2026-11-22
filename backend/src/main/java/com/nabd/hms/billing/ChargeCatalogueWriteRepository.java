package com.nabd.hms.billing;

import com.nabd.hms.organization.api.ChargeHeadWriter;
import com.nabd.hms.pharmacy.api.ItemCatalogueWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PLT-036: the writes on charge_catalogue that clinic setup (charge heads) and pharmacy (item rows) used to run themselves, moved
 * here so that billing is the only module that creates or changes rows. Billing's own tax write stays in TaxRepository. The cached
 * stock balance (stock_qty) is the one column this class never writes: it stays with pharmacy (PHL-003 AC1).
 */
@Repository
class ChargeCatalogueWriteRepository implements ChargeHeadWriter, ItemCatalogueWriter {

    private final JdbcTemplate jdbc;

    ChargeCatalogueWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── charge heads (organization.setup) ──

    @Override
    public UUID insertHead(UUID tenantId, ChargeHead h, String taxCategory, String sacHsn) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO charge_catalogue (id, tenant_id, code, name, category, base_amount, follow_up_amount, " +
                        "emergency_amount, tax_code, tax_rate_percent, doctor_override, active, effective_from, effective_to, display_order, " +
                        "tax_category, sac_hsn) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, h.code(), h.name(), h.category(), h.baseAmount(), h.followUpAmount(),
                h.emergencyAmount(), h.taxCode(), h.taxRatePercent(), h.doctorOverride(), h.active(),
                Date.valueOf(h.effectiveFrom()), h.effectiveTo() == null ? null : Date.valueOf(h.effectiveTo()),
                h.displayOrder(), taxCategory, sacHsn);
        return id;
    }

    @Override
    public void updateHead(UUID tenantId, UUID id, ChargeHead h) {
        jdbc.update("UPDATE charge_catalogue SET code = ?, name = ?, category = ?, base_amount = ?, follow_up_amount = ?, " +
                        "emergency_amount = ?, tax_code = ?, tax_rate_percent = ?, doctor_override = ?, active = ?, effective_from = ?, " +
                        "effective_to = ?, display_order = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                h.code(), h.name(), h.category(), h.baseAmount(), h.followUpAmount(),
                h.emergencyAmount(), h.taxCode(), h.taxRatePercent(), h.doctorOverride(), h.active(),
                Date.valueOf(h.effectiveFrom()), h.effectiveTo() == null ? null : Date.valueOf(h.effectiveTo()),
                h.displayOrder(), tenantId, id);
    }

    @Override
    public int copyHeads(UUID tenantId, List<String> rowsJson) {
        int n = 0;
        for (String row : rowsJson) {
            n += jdbc.update("INSERT INTO charge_catalogue SELECT r.* FROM (SELECT ?::jsonb AS j, ?::uuid AS tenant) src, "
                    + "jsonb_populate_record(NULL::charge_catalogue, src.j || jsonb_build_object('id', gen_random_uuid(), 'tenant_id', src.tenant, "
                    + "'created_at', now(), 'updated_at', now(), "
                    + "'stock_qty', CASE WHEN src.j->>'stock_qty' IS NOT NULL THEN 0 END)) r ON CONFLICT DO NOTHING", row, tenantId);
        }
        return n;
    }

    @Override
    public void deleteHeads(UUID tenantId) {
        jdbc.update("DELETE FROM charge_catalogue WHERE tenant_id = ?", tenantId);
    }

    // ── pharmacy items (pharmacy) ──

    @Override
    public UUID insertItem(UUID tenantId, String code, String name, String category, BigDecimal price, BigDecimal taxRatePercent,
                           boolean isRx, String hsnCode, LocalDate effectiveFrom) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO charge_catalogue (id, tenant_id, code, name, category, base_amount, tax_rate_percent, " +
                        "is_rx, hsn_code, active, effective_from) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, code, name, category, price, taxRatePercent,
                isRx, hsnCode, true, Date.valueOf(effectiveFrom));
        return id;
    }

    @Override
    public int updateItem(UUID tenantId, UUID id, String name, BigDecimal price, BigDecimal taxRatePercent, String hsnCode) {
        return jdbc.update(
                "UPDATE charge_catalogue SET name = ?, base_amount = ?, tax_rate_percent = ?, " +
                        "hsn_code = ?, updated_at = now() " +
                        "WHERE tenant_id = ? AND id = ? AND stock_qty IS NOT NULL",
                name, price, taxRatePercent, hsnCode,
                tenantId, id);
    }

    @Override
    public void patchItem(UUID tenantId, UUID id, Boolean active, String drugId) {
        jdbc.update("UPDATE charge_catalogue SET active = COALESCE(?, active), drug_id = COALESCE(?, drug_id), updated_at = now() " +
                "WHERE tenant_id = ? AND id = ?", active, drugId, tenantId, id);
    }
}
