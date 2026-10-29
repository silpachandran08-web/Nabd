package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** CLN-021: proposals for historical free-text lines live in rx_coding_suggestions; prescription_items is only read. */
@Repository
class RxCodingSuggestionRepository {

    record Pending(UUID itemId, String drugName, String brandName) {
    }

    private final JdbcTemplate jdbc;

    RxCodingSuggestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Pending> pending(UUID tenantId, int limit) {
        return jdbc.query("SELECT i.id, i.drug_name, i.brand_name FROM prescription_items i " +
                        "LEFT JOIN rx_coding_suggestions s ON s.tenant_id = i.tenant_id AND s.prescription_item_id = i.id " +
                        "WHERE i.tenant_id = ? AND i.drug_id IS NULL AND s.id IS NULL ORDER BY i.id LIMIT ?",
                (rs, n) -> new Pending(UUID.fromString(rs.getString("id")), rs.getString("drug_name"), rs.getString("brand_name")),
                tenantId, limit);
    }

    int insert(UUID tenantId, UUID itemId, String drugId, double confidence) {
        return jdbc.update("INSERT INTO rx_coding_suggestions (tenant_id, prescription_item_id, suggested_drug_id, confidence) " +
                        "VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, prescription_item_id) DO NOTHING",
                tenantId, itemId, drugId, BigDecimal.valueOf(confidence));
    }

    List<String> forItem(UUID tenantId, UUID itemId) {
        return jdbc.queryForList("SELECT suggested_drug_id FROM rx_coding_suggestions WHERE tenant_id = ? AND prescription_item_id = ?",
                String.class, tenantId, itemId);
    }
}
