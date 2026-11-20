package com.nabd.hms.lab;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PHL-041: lab_tests, lab_panels and lab_panel_tests (V3540). Prices are not here: they are the linked charge_catalogue head's. */
@Repository
class LabRepository {

    record TestRow(UUID id, String code, String name, String loinc, String sampleType, String container, BigDecimal volumeMl,
                   String method, String unit, int tatMinutes, UUID chargeItemId, boolean active, Instant updatedAt) {
    }

    record PanelRow(UUID id, String code, String name, UUID chargeItemId, boolean active) {
    }

    record PanelTestRow(UUID panelId, UUID testId) {
    }

    private static final String TEST_COLS = "id, code, name, loinc, sample_type, container, volume_ml, method, unit, tat_minutes, "
            + "charge_item_id, active, updated_at";
    private static final RowMapper<TestRow> TEST = (rs, i) -> new TestRow(rs.getObject("id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getString("loinc"), rs.getString("sample_type"), rs.getString("container"),
            rs.getBigDecimal("volume_ml"), rs.getString("method"), rs.getString("unit"), rs.getInt("tat_minutes"),
            rs.getObject("charge_item_id", UUID.class), rs.getBoolean("active"), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    LabRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Keyset page by code; like = an escaped ILIKE pattern over code, name and LOINC, or null. */
    List<TestRow> tests(UUID tenantId, String like, String afterCode, int limit) {
        return jdbc.query("SELECT " + TEST_COLS + " FROM lab_tests WHERE tenant_id = ? "
                        + "AND (?::text IS NULL OR code ILIKE ? OR name ILIKE ? OR loinc ILIKE ?) AND (?::text IS NULL OR code > ?) "
                        + "ORDER BY code LIMIT ?", TEST, tenantId, like, like, like, like, afterCode, afterCode, limit);
    }

    Optional<TestRow> testForUpdate(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + TEST_COLS + " FROM lab_tests WHERE tenant_id = ? AND id = ? FOR UPDATE", TEST, tenantId, id)
                .stream().findFirst();
    }

    List<TestRow> testsByIds(UUID tenantId, Collection<UUID> ids) {
        return jdbc.query("SELECT " + TEST_COLS + " FROM lab_tests WHERE tenant_id = ? AND id = ANY (?)", TEST, tenantId,
                ids.toArray(new UUID[0]));
    }

    Set<String> testCodesIn(UUID tenantId, Collection<String> codes) {
        return new HashSet<>(jdbc.queryForList("SELECT code FROM lab_tests WHERE tenant_id = ? AND code = ANY (?)", String.class,
                tenantId, codes.toArray(new String[0])));
    }

    boolean panelCodeExists(UUID tenantId, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM lab_panels WHERE tenant_id = ? AND code = ?)",
                Boolean.class, tenantId, code));
    }

    UUID insertTest(UUID tenantId, TestRow t) {
        return jdbc.queryForObject("INSERT INTO lab_tests (tenant_id, code, name, loinc, sample_type, container, volume_ml, method, unit, "
                        + "tat_minutes, charge_item_id, active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id", UUID.class,
                tenantId, t.code(), t.name(), t.loinc(), t.sampleType(), t.container(), t.volumeMl(), t.method(), t.unit(),
                t.tatMinutes(), t.chargeItemId(), t.active());
    }

    void updateTest(UUID tenantId, TestRow t) {
        jdbc.update("UPDATE lab_tests SET name = ?, loinc = ?, sample_type = ?, container = ?, volume_ml = ?, method = ?, unit = ?, "
                        + "tat_minutes = ?, active = ? WHERE tenant_id = ? AND id = ?",
                t.name(), t.loinc(), t.sampleType(), t.container(), t.volumeMl(), t.method(), t.unit(), t.tatMinutes(), t.active(),
                tenantId, t.id());
    }

    UUID insertPanel(UUID tenantId, String code, String name, UUID chargeItemId, List<UUID> testIds) {
        UUID id = jdbc.queryForObject("INSERT INTO lab_panels (tenant_id, code, name, charge_item_id) VALUES (?,?,?,?) RETURNING id",
                UUID.class, tenantId, code, name, chargeItemId);
        for (int i = 0; i < testIds.size(); i++) {
            jdbc.update("INSERT INTO lab_panel_tests (tenant_id, panel_id, test_id, display_order) VALUES (?,?,?,?)",
                    tenantId, id, testIds.get(i), i + 1);
        }
        return id;
    }

    List<PanelRow> panels(UUID tenantId) {
        return jdbc.query("SELECT id, code, name, charge_item_id, active FROM lab_panels WHERE tenant_id = ? ORDER BY code",
                (rs, i) -> new PanelRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getObject("charge_item_id", UUID.class), rs.getBoolean("active")), tenantId);
    }

    /** Every panel's tests in panel display order. */
    List<PanelTestRow> panelTests(UUID tenantId) {
        return jdbc.query("SELECT panel_id, test_id FROM lab_panel_tests WHERE tenant_id = ? ORDER BY panel_id, display_order",
                (rs, i) -> new PanelTestRow(rs.getObject("panel_id", UUID.class), rs.getObject("test_id", UUID.class)), tenantId);
    }
}
