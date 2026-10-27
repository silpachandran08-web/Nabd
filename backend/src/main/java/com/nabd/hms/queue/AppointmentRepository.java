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
            "id, tenant_id, patient_id, doctor_id, start_time, end_time, status, created_at, is_follow_up, cancel_reason, decision_source, channel, booking_kind, session_token ";

    private final JdbcTemplate jdbc;
    private final com.nabd.hms.organization.api.BookingRules bookingRules;

    AppointmentRepository(JdbcTemplate jdbc, com.nabd.hms.organization.api.BookingRules bookingRules) {
        this.jdbc = jdbc;
        this.bookingRules = bookingRules;
    }

    /** OPS-006: empty = no such appointment in this clinic; a null element = untouched since V2603 (reads as 0). */
    java.util.List<Long> changeSeq(UUID tenantId, UUID id) {
        return jdbc.query("SELECT change_seq FROM appointments WHERE tenant_id = ? AND id = ?", (rs, i) -> rs.getObject(1, Long.class), tenantId, id);
    }

    /** Throws DataIntegrityViolationException if the slot is already booked — see uq_appointments_doctor_slot. */
    UUID insert(UUID tenantId, UUID patientId, UUID doctorId, Instant startTime, Instant endTime, boolean isFollowUp, String decisionSource) {
        UUID id = com.nabd.hms.shared.UuidV7.next();
        // ACC-008: every booking path stamps the policy version it was made under (null before the first publish)
        jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, is_follow_up, decision_source, policy_version) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenantId, patientId, doctorId, Timestamp.from(startTime), Timestamp.from(endTime), isFollowUp, decisionSource,
                bookingRules.publishedVersion().orElse(null));
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

    List<AppointmentRow> listPage(UUID tenantId, UUID doctorId, UUID patientId, Instant dayFrom, Instant dayTo,
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
        if (dayFrom != null) { // ACC-005: the bounds of one clinic day
            sql.append("AND start_time >= ? AND start_time < ? ");
            params.add(Timestamp.from(dayFrom));
            params.add(Timestamp.from(dayTo));
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
        jdbc.update("UPDATE appointments SET status = ? WHERE tenant_id = ? AND id = ? AND status IN ('scheduled','arrived')",
                status, tenantId, id);
    }

    /** ACC-003: the atomic scheduled -> arrived flip; 0 rows means someone else already checked it in (or it moved on). */
    int markArrived(UUID tenantId, UUID id) {
        return jdbc.update("UPDATE appointments SET status = 'arrived', arrived_at = now() WHERE tenant_id = ? AND id = ? AND status = 'scheduled'",
                tenantId, id);
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
                rs.getBoolean("is_follow_up"),
                rs.getString("cancel_reason"), rs.getString("decision_source"), rs.getString("channel"),
                rs.getString("booking_kind"),
                (Integer) rs.getObject("session_token"));
    }

    /** ACC-011: a token booking takes the whole session window and a reserved number (no overlap constraint, V312). */
    UUID insertToken(UUID tenantId, UUID patientId, UUID doctorId, Instant start, Instant end, boolean isFollowUp,
                     UUID workingHoursId, int token) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, is_follow_up, " +
                        "booking_kind, working_hours_id, session_token) VALUES (?,?,?,?,?,?,?,'token',?,?)",
                id, tenantId, patientId, doctorId, Timestamp.from(start), Timestamp.from(end), isFollowUp, workingHoursId, token);
        return id;
    }

    /** QA-03: existence checked up front so an unknown id is a 404, not an FK violation mis-reported as a slot conflict. */
    boolean patientExists(UUID tenantId, UUID patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM patients WHERE tenant_id = ? AND id = ?)", Boolean.class, tenantId, patientId));
    }

    boolean doctorExists(UUID tenantId, UUID doctorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM staff WHERE tenant_id = ? AND id = ?)", Boolean.class, tenantId, doctorId));
    }
}
