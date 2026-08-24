package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.AppointmentRow;
import static com.nabd.hms.queue.QueueModels.CallbackEntryRow;

@Repository
class AppointmentRepository {

    private static final String COLUMNS =
            "id, tenant_id, patient_id, doctor_id, start_time, end_time, status, created_at, is_follow_up ";

    private final JdbcTemplate jdbc;

    AppointmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws DataIntegrityViolationException if the slot is already booked — see uq_appointments_doctor_slot. */
    UUID insert(UUID tenantId, UUID patientId, UUID doctorId, Instant startTime, Instant endTime, boolean isFollowUp) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, is_follow_up) " +
                        "VALUES (?,?,?,?,?,?,?)",
                id, tenantId, patientId, doctorId, Timestamp.from(startTime), Timestamp.from(endTime), isFollowUp);
        return id;
    }

    /** NB-116: no_show is an immediate miss; a still-scheduled follow-up 15+ days past start was never rebooked. */
    List<CallbackEntryRow> listCallbackList(UUID tenantId) {
        return jdbc.query(
                "SELECT a.id, a.patient_id, p.name AS patient_name, a.doctor_id, a.start_time, a.status " +
                        "FROM appointments a JOIN patients p ON p.id = a.patient_id " +
                        "WHERE a.tenant_id = ? AND a.is_follow_up = true " +
                        "AND (a.status = 'no_show' OR (a.status = 'scheduled' AND a.start_time < now() - interval '15 days')) " +
                        "ORDER BY a.start_time",
                (rs, i) -> new CallbackEntryRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("patient_id")),
                        rs.getString("patient_name"), UUID.fromString(rs.getString("doctor_id")),
                        rs.getTimestamp("start_time").toInstant(), rs.getString("status")),
                tenantId);
    }

    Optional<AppointmentRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + "FROM appointments WHERE tenant_id = ? AND id = ?",
                mapper(), tenantId, id).stream().findFirst();
    }

    List<AppointmentRow> listPage(UUID tenantId, UUID doctorId, UUID patientId, LocalDate date,
                                   int limit, Instant afterCreatedAt, UUID afterId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + "FROM appointments WHERE tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (doctorId != null) {
            sql.append("AND doctor_id = ? ");
            params.add(doctorId);
        }
        if (patientId != null) {
            sql.append("AND patient_id = ? ");
            params.add(patientId);
        }
        if (date != null) {
            sql.append("AND start_time >= ? AND start_time < ? ");
            params.add(Timestamp.from(date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
            params.add(Timestamp.from(date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        }
        if (afterCreatedAt != null) {
            sql.append("AND (created_at, id) > (?, ?) ");
            params.add(Timestamp.from(afterCreatedAt));
            params.add(afterId);
        }
        sql.append("ORDER BY created_at, id LIMIT ?");
        params.add(limit);

        return jdbc.query(sql.toString(), mapper(), params.toArray());
    }

    void cancel(UUID tenantId, UUID id, String reason) {
        jdbc.update("UPDATE appointments SET status = 'cancelled', cancel_reason = ? " +
                "WHERE tenant_id = ? AND id = ? AND status = 'scheduled'", reason, tenantId, id);
    }

    void markTerminal(UUID tenantId, UUID id, String status) {
        jdbc.update("UPDATE appointments SET status = ? WHERE tenant_id = ? AND id = ? AND status = 'scheduled'",
                status, tenantId, id);
    }

    private RowMapper<AppointmentRow> mapper() {
        return (rs, i) -> new AppointmentRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("patient_id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getTimestamp("start_time").toInstant(),
                rs.getTimestamp("end_time").toInstant(),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("is_follow_up"));
    }
}
