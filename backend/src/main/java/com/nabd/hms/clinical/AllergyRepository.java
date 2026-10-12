package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.ActorInfo;
import static com.nabd.hms.clinical.AllergyModels.AllergyEventRow;
import static com.nabd.hms.clinical.AllergyModels.AllergyRow;
import static com.nabd.hms.clinical.AllergyModels.AssessmentRow;

@Repository
class AllergyRepository {

    private final JdbcTemplate jdbc;

    AllergyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AllergyRow> findActiveByPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT * FROM patient_allergies WHERE tenant_id = ? AND patient_id = ? AND active " +
                        "ORDER BY recorded_at DESC",
                mapper(), tenantId, patientId);
    }

    /** V1-004/V1-005: transaction-scoped advisory lock on one patient's allergy register. Prescription save/sign and
     * every register write take it first, so an allergy recorded or downgraded concurrently cannot slip between a
     * sign-time warning check and the status flip. Always acquired before any row lock. */
    void lockRegister(UUID tenantId, UUID patientId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "allergy-register:" + tenantId + ":" + patientId);
    }

    /** V1-005: row lock so a correction reads the values it is about to change, not a stale copy. */
    Optional<AllergyRow> findByIdForUpdate(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM patient_allergies WHERE tenant_id = ? AND id = ? FOR UPDATE", mapper(), tenantId, id)
                .stream().findFirst();
    }

    Optional<AllergyRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM patient_allergies WHERE tenant_id = ? AND id = ?", mapper(), tenantId, id)
                .stream().findFirst();
    }

    boolean patientExists(UUID tenantId, UUID patientId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ? AND id = ?", Integer.class,
                tenantId, patientId);
        return n != null && n > 0;
    }

    UUID insert(UUID tenantId, UUID patientId, String substance, String category, String severity, String reaction,
                String source, UUID recordedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patient_allergies (id, tenant_id, patient_id, substance, category, severity, reaction, " +
                        "source, recorded_by) VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenantId, patientId, substance, category, severity, reaction, source, recordedBy);
        return id;
    }

    /** Full-row write of the (already merged) corrected values; the caller keeps the original in the history. */
    void applyCorrection(UUID tenantId, UUID id, String substance, String category, String severity, String reaction,
                         String source, UUID updatedBy) {
        jdbc.update("UPDATE patient_allergies SET substance = ?, category = ?, severity = ?, reaction = ?, source = ?, " +
                        "updated_by = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                substance, category, severity, reaction, source, updatedBy, tenantId, id);
    }

    /** Returns affected rows; only an active entry can be removed. */
    int deactivate(UUID tenantId, UUID id, UUID by, String reason) {
        return jdbc.update("UPDATE patient_allergies SET active = false, deactivated_by = ?, deactivated_at = now(), " +
                        "deactivation_reason = ? WHERE tenant_id = ? AND id = ? AND active",
                by, reason, tenantId, id);
    }

    void insertEvent(UUID tenantId, UUID patientId, UUID allergyId, String event, String beforeJson, String afterJson,
                     String reason, UUID actorId) {
        jdbc.update("INSERT INTO patient_allergy_events (tenant_id, patient_id, allergy_id, event, before, after, reason, " +
                        "actor_id) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)",
                tenantId, patientId, allergyId, event, beforeJson, afterJson, reason, actorId);
    }

    List<AllergyEventRow> findEvents(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT id, allergy_id, event, before::text AS before_json, after::text AS after_json, reason, " +
                        "actor_id, occurred_at FROM patient_allergy_events WHERE tenant_id = ? AND patient_id = ? " +
                        "ORDER BY occurred_at DESC, id",
                (rs, i) -> new AllergyEventRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("allergy_id")),
                        rs.getString("event"), rs.getString("before_json"), rs.getString("after_json"), rs.getString("reason"),
                        UUID.fromString(rs.getString("actor_id")), rs.getTimestamp("occurred_at").toInstant()),
                tenantId, patientId);
    }

    Optional<AssessmentRow> findAssessment(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT documented_by, documented_at FROM patient_allergy_assessments WHERE tenant_id = ? AND patient_id = ?",
                (rs, i) -> new AssessmentRow(UUID.fromString(rs.getString("documented_by")), rs.getTimestamp("documented_at").toInstant()),
                tenantId, patientId).stream().findFirst();
    }

    void upsertAssessment(UUID tenantId, UUID patientId, UUID by) {
        jdbc.update("INSERT INTO patient_allergy_assessments (tenant_id, patient_id, status, documented_by) " +
                        "VALUES (?,?,'no_known_allergies',?) ON CONFLICT (tenant_id, patient_id) DO UPDATE " +
                        "SET documented_by = EXCLUDED.documented_by, documented_at = now()",
                tenantId, patientId, by);
    }

    int deleteAssessment(UUID tenantId, UUID patientId) {
        return jdbc.update("DELETE FROM patient_allergy_assessments WHERE tenant_id = ? AND patient_id = ?", tenantId, patientId);
    }

    /** NB-108: actor_name/actor_role snapshot for audit rows. */
    Optional<ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    Map<UUID, String> findStaffNames(UUID tenantId, Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        for (UUID id : ids) {
            jdbc.query("SELECT name FROM staff WHERE tenant_id = ? AND id = ?", rs -> {
                names.put(id, rs.getString("name"));
            }, tenantId, id);
        }
        return names;
    }

    private RowMapper<AllergyRow> mapper() {
        return (rs, i) -> new AllergyRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getString("substance"),
                rs.getString("severity"),
                rs.getString("reaction"),
                rs.getBoolean("active"),
                UUID.fromString(rs.getString("recorded_by")),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("category"),
                rs.getString("source"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }
}
