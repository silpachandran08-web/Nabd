package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the Schedule board — every query is tenant-scoped (callers set TenantContext first). */
@Repository
class ScheduleBoardRepository {

    record DoctorRow(UUID id, String name, String department) {
    }

    record BlockRow(UUID doctorId, LocalTime start, LocalTime end, int slotMinutes) {
    }

    record LeaveRow(UUID doctorId, String reason) {
    }

    record AppointmentRow(UUID id, UUID doctorId, UUID patientId, String patientName, Instant start, Instant end,
                          String status, boolean followUp, UUID queueEntryId, Integer token, String queueStatus) {
    }

    record WalkInRow(UUID queueEntryId, UUID doctorId, UUID patientId, String patientName, Instant createdAt,
                     String source, int token, String status) {
    }

    private final JdbcTemplate jdbc;

    ScheduleBoardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Same match rules as ScheduleRepository.isClinicHoliday (recurring = same month and day). */
    Optional<String> holidayName(UUID tenantId, LocalDate date) {
        return jdbc.query("""
                SELECT name FROM clinic_holidays WHERE tenant_id = ? AND (
                  (NOT recurring AND holiday_date = ?) OR
                  (recurring AND EXTRACT(MONTH FROM holiday_date) = ? AND EXTRACT(DAY FROM holiday_date) = ?))
                ORDER BY name LIMIT 1
                """, (rs, i) -> rs.getString(1), tenantId, Date.valueOf(date), date.getMonthValue(), date.getDayOfMonth())
                .stream().findFirst();
    }

    /** The schedule's columns: active staff who have working hours on any weekday or hold a Doctor
     * role (same ILIKE 'doctor' rule as DepartmentRepository's transfer roster). */
    List<DoctorRow> doctors(UUID tenantId) {
        return jdbc.query("""
                SELECT s.id, s.name, d.name AS department FROM staff s
                JOIN roles r ON r.id = s.role_id
                LEFT JOIN departments d ON d.id = s.department_id
                WHERE s.tenant_id = ? AND s.status = 'active'
                  AND (r.name ILIKE 'doctor' OR EXISTS (SELECT 1 FROM doctor_working_hours w WHERE w.doctor_id = s.id))
                ORDER BY s.name
                """, (rs, i) -> new DoctorRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("department")),
                tenantId);
    }

    /** Anyone a booking points at on this day, so a booking is never dropped for want of a column. */
    List<DoctorRow> staffByIds(UUID tenantId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT s.id, s.name, d.name AS department FROM staff s LEFT JOIN departments d ON d.id = s.department_id
                WHERE s.tenant_id = ? AND s.id = ANY (?)
                """, (rs, i) -> new DoctorRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("department")),
                tenantId, ids.toArray(new UUID[0]));
    }

    List<BlockRow> blocksFor(UUID tenantId, int dayOfWeek) {
        return jdbc.query("SELECT doctor_id, start_time, end_time, slot_minutes FROM doctor_working_hours " +
                        "WHERE tenant_id = ? AND day_of_week = ? ORDER BY start_time",
                (rs, i) -> new BlockRow(rs.getObject("doctor_id", UUID.class), rs.getTime("start_time").toLocalTime(),
                        rs.getTime("end_time").toLocalTime(), rs.getInt("slot_minutes")),
                tenantId, dayOfWeek);
    }

    List<LeaveRow> leaveOn(UUID tenantId, LocalDate date) {
        return jdbc.query("SELECT doctor_id, reason FROM doctor_leave WHERE tenant_id = ? AND ? BETWEEN date_from AND date_to",
                (rs, i) -> new LeaveRow(rs.getObject("doctor_id", UUID.class), rs.getString("reason")),
                tenantId, Date.valueOf(date));
    }

    /** Non-cancelled appointments starting in [dayStart, dayEnd), with the queue entry once the patient arrived. */
    List<AppointmentRow> appointmentsBetween(UUID tenantId, Instant dayStart, Instant dayEnd) {
        return jdbc.query("""
                SELECT a.id, a.doctor_id, a.patient_id, p.name AS patient_name, a.start_time, a.end_time, a.status,
                       a.is_follow_up, q.id AS queue_entry_id, q.token_number, q.status AS queue_status
                FROM appointments a
                JOIN patients p ON p.id = a.patient_id
                LEFT JOIN LATERAL (SELECT id, token_number, status FROM queue_entries
                                   WHERE appointment_id = a.id ORDER BY created_at DESC LIMIT 1) q ON true
                WHERE a.tenant_id = ? AND a.status <> 'cancelled' AND a.start_time >= ? AND a.start_time < ?
                ORDER BY a.start_time
                """, (rs, i) -> new AppointmentRow(rs.getObject("id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
                        rs.getTimestamp("start_time").toInstant(), rs.getTimestamp("end_time").toInstant(),
                        rs.getString("status"), rs.getBoolean("is_follow_up"), rs.getObject("queue_entry_id", UUID.class),
                        (Integer) rs.getObject("token_number"), rs.getString("queue_status")),
                tenantId, Timestamp.from(dayStart), Timestamp.from(dayEnd));
    }

    /** Queue entries that didn't come from an appointment: walk-ins and department transfers. */
    List<WalkInRow> walkInsOn(UUID tenantId, LocalDate date) {
        return jdbc.query("""
                SELECT q.id, q.doctor_id, q.patient_id, p.name AS patient_name, q.created_at, q.source, q.token_number, q.status
                FROM queue_entries q JOIN patients p ON p.id = q.patient_id
                WHERE q.tenant_id = ? AND q.queue_date = ? AND q.appointment_id IS NULL
                ORDER BY q.created_at
                """, (rs, i) -> new WalkInRow(rs.getObject("id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("source"), rs.getInt("token_number"),
                        rs.getString("status")),
                tenantId, Date.valueOf(date));
    }
}
