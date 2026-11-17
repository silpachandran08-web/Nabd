package com.nabd.hms.engagement;

import com.nabd.hms.engagement.dto.RecallOrderResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class RecallRepository {

    record Encounter(UUID patientId, UUID doctorId, LocalDate visitDate) {
    }

    private static final String COLS = "id, due_date, status, interval_days, reason_code, booked_appointment_id";
    private static final RowMapper<RecallOrderResponse> ROW = (rs, i) -> new RecallOrderResponse(
            rs.getObject("id", UUID.class), rs.getObject("due_date", LocalDate.class), rs.getString("status"),
            (Integer) rs.getObject("interval_days"), rs.getString("reason_code"), rs.getObject("booked_appointment_id", UUID.class));

    private final JdbcTemplate jdbc;

    RecallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The visit (queue entry) the consult closes; queue_date is already the clinic-local date (ClinicClock at check-in). */
    Optional<Encounter> encounter(UUID tenantId, UUID encounterId) {
        return jdbc.query("SELECT patient_id, doctor_id, queue_date FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Encounter(rs.getObject("patient_id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getObject("queue_date", LocalDate.class)), tenantId, encounterId).stream().findFirst();
    }

    /** The soonest scheduled follow-up with this doctor still ahead: booked before the recall chip was tapped. */
    Optional<UUID> upcomingFollowUp(UUID tenantId, UUID patientId, UUID doctorId, Instant now) {
        return jdbc.queryForList("SELECT id FROM appointments WHERE tenant_id = ? AND patient_id = ? AND doctor_id = ? "
                        + "AND is_follow_up AND status = 'scheduled' AND start_time > ? ORDER BY start_time LIMIT 1",
                UUID.class, tenantId, patientId, doctorId, Timestamp.from(now)).stream().findFirst();
    }

    UUID insert(UUID tenantId, UUID patientId, UUID encounterId, UUID doctorId, UUID ruleId, String reasonCode, int intervalDays,
                LocalDate dueDate, String status, UUID bookedAppointmentId, UUID createdBy) {
        return jdbc.queryForObject("INSERT INTO recall_orders (tenant_id, patient_id, source_encounter_id, doctor_id, rule_id, source, "
                        + "reason_code, interval_days, due_date, status, booked_appointment_id, created_by) "
                        + "VALUES (?,?,?,?,?,'consult',?,?,?,?,?,?) RETURNING id", UUID.class,
                tenantId, patientId, encounterId, doctorId, ruleId, reasonCode, intervalDays, Date.valueOf(dueDate), status,
                bookedAppointmentId, createdBy);
    }

    List<RecallOrderResponse> forEncounter(UUID tenantId, UUID encounterId) {
        return jdbc.query("SELECT " + COLS + " FROM recall_orders WHERE tenant_id = ? AND source_encounter_id = ?", ROW, tenantId, encounterId);
    }

    Optional<RecallOrderResponse> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM recall_orders WHERE tenant_id = ? AND id = ?", ROW, tenantId, id).stream().findFirst();
    }

    /** The patient's oldest-due open recall with this doctor (or none named) becomes 'booked'; returns its id. */
    Optional<UUID> markBooked(UUID tenantId, UUID patientId, UUID doctorId, UUID appointmentId) {
        return jdbc.queryForList("""
                UPDATE recall_orders SET status = 'booked', booked_appointment_id = ?, updated_at = now()
                 WHERE id = (SELECT id FROM recall_orders
                              WHERE tenant_id = ? AND patient_id = ? AND (doctor_id = ? OR doctor_id IS NULL)
                                AND status IN ('pending', 'messaged', 'callback')
                              ORDER BY due_date, created_at LIMIT 1 FOR UPDATE)
                RETURNING id""", UUID.class, appointmentId, tenantId, patientId, doctorId).stream().findFirst();
    }
}
