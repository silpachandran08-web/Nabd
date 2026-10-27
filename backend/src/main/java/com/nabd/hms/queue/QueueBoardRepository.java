package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ACC-022: the multi-doctor board in four reads (staff, entries, sessions, delays) and the reassign write. OPD only: class = 'ambulatory'. */
@Repository
class QueueBoardRepository {

    record DoctorRow(UUID id, String name) {
    }

    record EntryRow(UUID id, UUID patientId, UUID doctorId, int token, Integer previousToken, UUID reassignedFrom, String status,
                    boolean priority, String source, String patientName, String mrn, boolean allergy, boolean vitals,
                    Instant p50At, Instant p80At) {
    }

    private final JdbcTemplate jdbc;

    QueueBoardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DoctorRow> doctors(UUID tenantId, LocalDate day, UUID departmentId) {
        return jdbc.query("SELECT s.id, s.name FROM staff s WHERE s.tenant_id = ? AND (" +
                        "s.id IN (SELECT doctor_id FROM doctor_working_hours WHERE tenant_id = ?) OR " +
                        "s.id IN (SELECT doctor_id FROM queue_entries WHERE tenant_id = ? AND queue_date = ? AND class = 'ambulatory') OR " +
                        "s.id IN (SELECT doctor_id FROM doctor_sessions WHERE tenant_id = ? AND clinic_date = ?)) " +
                        "AND (?::uuid IS NULL OR s.department_id = ?::uuid) ORDER BY s.name, s.id",
                (rs, i) -> new DoctorRow(rs.getObject(1, UUID.class), rs.getString(2)),
                tenantId, tenantId, tenantId, Date.valueOf(day), tenantId, Date.valueOf(day), departmentId, departmentId);
    }

    List<EntryRow> entries(UUID tenantId, LocalDate day, UUID departmentId) {
        return jdbc.query("SELECT q.id, q.patient_id, q.doctor_id, q.token_number, q.previous_token, q.reassigned_from_doctor_id, q.status, " +
                        "q.priority, q.source, p.name, p.mrn, " +
                        "EXISTS (SELECT 1 FROM patient_allergies al WHERE al.tenant_id = q.tenant_id AND al.patient_id = q.patient_id AND al.active) AS allergy, " +
                        "EXISTS (SELECT 1 FROM vitals v WHERE v.tenant_id = q.tenant_id AND v.queue_entry_id = q.id) AS vitals, e.p50_at, e.p80_at " +
                        "FROM queue_entries q JOIN patients p ON p.id = q.patient_id " +
                        "LEFT JOIN LATERAL (SELECT l.p50_at, l.p80_at FROM eta_prediction_log l WHERE l.tenant_id = q.tenant_id AND l.queue_entry_id = q.id " +
                        "ORDER BY l.predicted_at DESC LIMIT 1) e ON true " +
                        "WHERE q.tenant_id = ? AND q.queue_date = ? AND q.class = 'ambulatory' " +
                        "AND q.status NOT IN ('completed','no_show','transferred_out') AND (?::uuid IS NULL OR q.department_id = ?::uuid) " +
                        "ORDER BY q.doctor_id, q.priority DESC, q.token_number",
                (rs, i) -> new EntryRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4),
                        (Integer) rs.getObject(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getBoolean(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getBoolean(12), rs.getBoolean(13),
                        rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toInstant(),
                        rs.getTimestamp(15) == null ? null : rs.getTimestamp(15).toInstant()),
                tenantId, Date.valueOf(day), departmentId, departmentId);
    }

    /** The doctor's latest session state of the day. */
    java.util.Map<UUID, String> sessionStates(UUID tenantId, LocalDate day) {
        var out = new java.util.HashMap<UUID, String>();
        jdbc.query("SELECT DISTINCT ON (doctor_id) doctor_id, state FROM doctor_sessions WHERE tenant_id = ? AND clinic_date = ? " +
                "ORDER BY doctor_id, started_at DESC", rs -> {
            out.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, tenantId, Date.valueOf(day));
        return out;
    }

    java.util.Map<UUID, Integer> activeDelays(UUID tenantId, LocalDate day, java.time.Instant now) {
        var out = new java.util.HashMap<UUID, Integer>();
        jdbc.query("SELECT doctor_id, delay_minutes FROM doctor_delays WHERE tenant_id = ? AND clinic_date = ? AND cleared_at IS NULL AND expires_at > ?", rs -> {
            out.put(rs.getObject(1, UUID.class), rs.getInt(2));
        }, tenantId, Date.valueOf(day), java.sql.Timestamp.from(now));
        return out;
    }

    /** Compare-and-set on status and doctor: 0 rows means someone moved the entry first. The old token is kept in previous_token. */
    int reassign(UUID tenantId, UUID id, String expectedStatus, UUID fromDoctor, UUID toDoctor, int newToken) {
        return jdbc.update("UPDATE queue_entries SET doctor_id = ?, token_number = ?, previous_token = token_number, reassigned_from_doctor_id = doctor_id " +
                "WHERE tenant_id = ? AND id = ? AND status = ? AND doctor_id = ?", toDoctor, newToken, tenantId, id, expectedStatus, fromDoctor);
    }
}
