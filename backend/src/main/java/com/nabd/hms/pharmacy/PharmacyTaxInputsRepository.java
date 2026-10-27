package com.nabd.hms.pharmacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PHL-021: tax inputs on pharmacy_item_profiles. */
@Repository
class PharmacyTaxInputsRepository {

    record Row(Boolean priceInclusive, boolean zeroRated) {
    }

    private final JdbcTemplate jdbc;

    PharmacyTaxInputsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<UUID, Row> inputs(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, Row> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT item_id, price_inclusive_of_tax, zero_rated FROM pharmacy_item_profiles WHERE tenant_id = ? AND item_id = ANY (?)",
                rs -> {
                    out.put(rs.getObject("item_id", UUID.class), new Row((Boolean) rs.getObject("price_inclusive_of_tax"), rs.getBoolean("zero_rated")));
                }, tenantId, ids.toArray(UUID[]::new));
        return out;
    }

    Optional<BigDecimal> mrp(UUID tenantId, UUID itemId) {
        return jdbc.query("SELECT mrp FROM pharmacy_item_profiles WHERE tenant_id = ? AND item_id = ? AND mrp IS NOT NULL",
                (rs, i) -> rs.getBigDecimal(1), tenantId, itemId).stream().findFirst();
    }

    /** Null arguments leave the stored value (PATCH semantics); the HSN copy follows charge_catalogue.hsn_code. */
    void setInputs(UUID tenantId, UUID itemId, Boolean priceInclusive, Boolean zeroRated, BigDecimal mrp) {
        jdbc.update("UPDATE pharmacy_item_profiles SET price_inclusive_of_tax = COALESCE(?, price_inclusive_of_tax), " +
                        "zero_rated = COALESCE(?, zero_rated), mrp = COALESCE(?, mrp), " +
                        "hsn_code = (SELECT hsn_code FROM charge_catalogue WHERE id = ? AND tenant_id = ?) WHERE tenant_id = ? AND item_id = ?",
                priceInclusive, zeroRated, mrp, itemId, tenantId, tenantId, itemId);
    }

    Optional<String> licenceJson(UUID tenantId) {
        return jdbc.query("SELECT drug_licence_numbers::text FROM pharmacy_settings WHERE tenant_id = ?",
                (rs, i) -> rs.getString(1), tenantId).stream().findFirst();
    }
}
