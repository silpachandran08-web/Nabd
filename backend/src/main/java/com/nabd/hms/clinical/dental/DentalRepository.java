package com.nabd.hms.clinical.dental;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.dental.DentalModels.ToothRow;

@Repository
class DentalRepository {

    private final JdbcTemplate jdbc;

    DentalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** NB-122: full chart, one row per tooth ever annotated (unannotated teeth are implicitly healthy). */
    List<ToothRow> findByPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT * FROM dental_chart_entries WHERE tenant_id = ? AND patient_id = ? " +
                        "ORDER BY tooth_number",
                mapper(), tenantId, patientId);
    }

    /** Current state only (unique per tooth) — each row carries its own updated_by/updated_at as
     * last-change provenance. A full per-tooth change history (NB-127) would need either a second
     * append-only table or wiring AuditService.record() into this call; skipped here since almost no
     * other module in this codebase calls AuditService yet (SupportAccessService is the only caller)
     * and adding it just for Dental would be new, inconsistent scope beyond proving the framework. */
    UUID upsert(UUID tenantId, UUID patientId, int toothNumber, String status, String note, UUID updatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dental_chart_entries (id, tenant_id, patient_id, tooth_number, status, note, updated_by) " +
                        "VALUES (?,?,?,?,?,?,?) " +
                        "ON CONFLICT (tenant_id, patient_id, tooth_number) DO UPDATE SET status = EXCLUDED.status, " +
                        "  note = EXCLUDED.note, updated_by = EXCLUDED.updated_by, updated_at = now()",
                id, tenantId, patientId, toothNumber, status, note, updatedBy);
        return jdbc.queryForObject("SELECT id FROM dental_chart_entries WHERE tenant_id = ? AND patient_id = ? AND tooth_number = ?",
                UUID.class, tenantId, patientId, toothNumber);
    }

    private RowMapper<ToothRow> mapper() {
        return (rs, i) -> new ToothRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getInt("tooth_number"),
                rs.getString("status"),
                rs.getString("note"),
                UUID.fromString(rs.getString("updated_by")),
                rs.getTimestamp("updated_at").toInstant());
    }
}
