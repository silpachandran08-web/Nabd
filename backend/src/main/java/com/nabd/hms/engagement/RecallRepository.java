package com.nabd.hms.engagement;

import com.nabd.hms.engagement.dto.RecallOrderResponse;
import com.nabd.hms.engagement.dto.VaccineDueResponse;
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

    /**
     * CLN-043: open vaccine recalls due by {@code today} of the patients this doctor has today: an appointment still
     * scheduled/arrived in [from, to), or a queue entry of that clinic date (arrived). Consent gate as every clinical read;
     * own-patients holds by construction (each row is the caller's own appointment or queue entry).
     */
    List<VaccineDueResponse> vaccinesDue(UUID tenantId, UUID doctorId, LocalDate today, Instant from, Instant to) {
        return jdbc.query("""
                WITH today AS (
                  SELECT patient_id, bool_or(arrived) AS arrived FROM (
                    SELECT patient_id, true AS arrived FROM queue_entries
                     WHERE tenant_id = ? AND doctor_id = ? AND queue_date = ? AND status NOT IN ('no_show', 'transferred_out')
                    UNION ALL
                    SELECT patient_id, status = 'arrived' FROM appointments
                     WHERE tenant_id = ? AND doctor_id = ? AND status IN ('scheduled', 'arrived') AND start_time >= ? AND start_time < ?
                  ) t GROUP BY patient_id)
                SELECT r.id, r.patient_id, r.due_date, r.reason_code, today.arrived
                  FROM recall_orders r JOIN today ON today.patient_id = r.patient_id
                 WHERE r.tenant_id = ? AND r.source = 'vaccine' AND r.status IN ('pending', 'messaged', 'booked', 'callback')
                   AND r.due_date <= ?
                """ + com.nabd.hms.patient.api.ConsentGateSql.gate("r.patient_id", "r.tenant_id")
                        + "ORDER BY r.due_date, r.id",
                (rs, i) -> new VaccineDueResponse(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("due_date", LocalDate.class), rs.getString("reason_code"), rs.getBoolean("arrived")),
                tenantId, doctorId, Date.valueOf(today), tenantId, doctorId, Timestamp.from(from), Timestamp.from(to),
                tenantId, Date.valueOf(today));
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
