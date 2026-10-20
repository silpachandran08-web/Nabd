package com.nabd.hms.clinical.dental;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.dental.DentalModels.HistoryEntryRow;
import static com.nabd.hms.clinical.dental.DentalModels.ToothRow;

@Repository
class DentalRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    DentalRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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

    /** CLN-006 (MF-52): one append-only row per chart change, written in the same transaction as the change. */
    void insertEvent(UUID tenantId, UUID patientId, UUID entryId, int toothNumber, String event,
                     Map<String, Object> before, Map<String, Object> after, UUID actorId) {
        jdbc.update("INSERT INTO dental_chart_events (tenant_id, patient_id, entry_id, tooth_number, event, before, after, actor_id) " +
                        "VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?)",
                tenantId, patientId, entryId, toothNumber, event, json(before), json(after), actorId);
    }

    /** NB-127 / CLN-006: the tooth's full timeline, from dental_chart_events (never from audit_log). entry_id is
     * stable across updates (inserted once, then ON CONFLICT DO UPDATE). Actor name/role are read at query time. */
    List<HistoryEntryRow> findHistory(UUID tenantId, UUID entryId) {
        return jdbc.query("SELECT COALESCE(s.name, '') AS actor_name, COALESCE(r.name, '') AS actor_role, " +
                        // the response keeps NB-127's action vocabulary, so the API contract does not change
                        "  CASE WHEN ev.event = 'supernumerary_removed' THEN 'dental_chart.delete' " +
                        "       WHEN ev.before IS NULL THEN 'dental_chart.create' ELSE 'dental_chart.update' END AS action, " +
                        "  ev.before::text AS before_text, ev.after::text AS after_text, ev.occurred_at " +
                        "FROM dental_chart_events ev LEFT JOIN staff s ON s.tenant_id = ev.tenant_id AND s.id = ev.actor_id " +
                        "LEFT JOIN roles r ON r.id = s.role_id " +
                        "WHERE ev.tenant_id = ? AND ev.entry_id = ? ORDER BY ev.occurred_at, ev.id",
                (rs, i) -> new HistoryEntryRow(rs.getString("actor_name"), rs.getString("actor_role"),
                        rs.getString("action"), rs.getString("before_text"), rs.getString("after_text"),
                        rs.getTimestamp("occurred_at").toInstant()),
                tenantId, entryId);
    }

    private String json(Map<String, Object> m) {
        try {
            return m == null ? null : objectMapper.writeValueAsString(m);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("dental chart snapshot is not serializable", e);
        }
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
