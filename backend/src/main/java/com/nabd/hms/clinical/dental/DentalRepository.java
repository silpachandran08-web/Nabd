package com.nabd.hms.clinical.dental;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.dental.DentalModels.ActorInfo;
import static com.nabd.hms.clinical.dental.DentalModels.HistoryEntryRow;
import static com.nabd.hms.clinical.dental.DentalModels.ToothRow;

@Repository
class DentalRepository {

    private final JdbcTemplate jdbc;

    DentalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** NB-122: full chart, one row per tooth ever annotated (unannotated teeth are implicitly healthy),
     * plus any supernumerary teeth — the frontend separates the two by isSupernumerary. */
    List<ToothRow> findByPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT * FROM dental_chart_entries WHERE tenant_id = ? AND patient_id = ? " +
                        "ORDER BY tooth_number",
                mapper(), tenantId, patientId);
    }

    Optional<ToothRow> findById(UUID tenantId, UUID patientId, UUID id) {
        return jdbc.query("SELECT * FROM dental_chart_entries WHERE tenant_id = ? AND patient_id = ? AND id = ?",
                mapper(), tenantId, patientId, id).stream().findFirst();
    }

    Optional<ToothRow> findByToothNumber(UUID tenantId, UUID patientId, int toothNumber) {
        return jdbc.query("SELECT * FROM dental_chart_entries WHERE tenant_id = ? AND patient_id = ? " +
                        "AND tooth_number = ? AND NOT is_supernumerary",
                mapper(), tenantId, patientId, toothNumber).stream().findFirst();
    }

    /** Upsert for a standard (non-supernumerary) tooth — one row per tooth_number, enforced by the
     * partial unique index (NB-122's schema change lets supernumerary rows share the same number). */
    void upsert(UUID tenantId, UUID patientId, int toothNumber, String status, String note, UUID updatedBy) {
        jdbc.update("INSERT INTO dental_chart_entries (id, tenant_id, patient_id, tooth_number, status, note, updated_by) " +
                        "VALUES (gen_random_uuid(),?,?,?,?,?,?) " +
                        "ON CONFLICT (tenant_id, patient_id, tooth_number) WHERE NOT is_supernumerary " +
                        "DO UPDATE SET status = EXCLUDED.status, note = EXCLUDED.note, " +
                        "  updated_by = EXCLUDED.updated_by, updated_at = now()",
                tenantId, patientId, toothNumber, status, note, updatedBy);
    }

    UUID insertSupernumerary(UUID tenantId, UUID patientId, int nearToothNumber, String status, String note, UUID updatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dental_chart_entries (id, tenant_id, patient_id, tooth_number, status, note, " +
                        "  updated_by, is_supernumerary) VALUES (?,?,?,?,?,?,?,true)",
                id, tenantId, patientId, nearToothNumber, status, note, updatedBy);
        return id;
    }

    /** Returns affected rows so the service can 404 an id that isn't this patient's supernumerary tooth. */
    int updateSupernumerary(UUID tenantId, UUID patientId, UUID id, String status, String note, UUID updatedBy) {
        return jdbc.update("UPDATE dental_chart_entries SET status = ?, note = ?, updated_by = ?, updated_at = now() " +
                        "WHERE tenant_id = ? AND patient_id = ? AND id = ? AND is_supernumerary",
                status, note, updatedBy, tenantId, patientId, id);
    }

    int deleteSupernumerary(UUID tenantId, UUID patientId, UUID id) {
        return jdbc.update("DELETE FROM dental_chart_entries WHERE tenant_id = ? AND patient_id = ? AND id = ? " +
                "AND is_supernumerary", tenantId, patientId, id);
    }

    /** NB-127: actor_name/actor_role snapshot for the audit row a chart write makes. */
    Optional<ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    /** NB-127: the tooth's full timeline. dental_chart_entries.id is stable across updates (only
     * inserted once, then ON CONFLICT DO UPDATE), so filtering audit_log by entity_id gives every
     * change ever made to this one row — no separate history table needed. */
    List<HistoryEntryRow> findHistory(UUID tenantId, UUID entityId) {
        return jdbc.query("SELECT actor_name, actor_role, action, before::text AS before_text, " +
                        "  after::text AS after_text, created_at " +
                        "FROM audit_log WHERE tenant_id = ? AND entity_type = 'dental_chart_entries' AND entity_id = ? " +
                        "ORDER BY created_at",
                (rs, i) -> new HistoryEntryRow(rs.getString("actor_name"), rs.getString("actor_role"),
                        rs.getString("action"), rs.getString("before_text"), rs.getString("after_text"),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, entityId);
    }

    private RowMapper<ToothRow> mapper() {
        return (rs, i) -> new ToothRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getInt("tooth_number"),
                rs.getString("status"),
                rs.getString("note"),
                rs.getBoolean("is_supernumerary"),
                UUID.fromString(rs.getString("updated_by")),
                rs.getTimestamp("updated_at").toInstant());
    }
}
