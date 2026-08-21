package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.DoctorLeaveRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Repository
class ScheduleRepository {

    private final JdbcTemplate jdbc;

    ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** NB-092: clinic_holidays already existed (CRUD via com.nabd.hms.setup) but nothing in
     * scheduling ever consulted it — a holiday blocked nothing. recurring=true matches by
     * month+day across any year (e.g. a fixed public holiday); non-recurring matches the exact date. */
    boolean isClinicHoliday(UUID tenantId, LocalDate date) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM clinic_holidays WHERE tenant_id = ? AND (" +
                        "  (NOT recurring AND holiday_date = ?) OR " +
                        "  (recurring AND EXTRACT(MONTH FROM holiday_date) = ? AND EXTRACT(DAY FROM holiday_date) = ?)" +
                        "))",
                Boolean.class, tenantId, Date.valueOf(date), date.getMonthValue(), date.getDayOfMonth());
        return Boolean.TRUE.equals(exists);
    }

    List<WorkingHoursRow> listWorkingHours(UUID doctorId) {
        return jdbc.query(
                "SELECT id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients " +
                        "FROM doctor_working_hours WHERE doctor_id = ? ORDER BY day_of_week, start_time",
                workingHoursMapper(), doctorId);
    }

    List<WorkingHoursRow> findForDay(UUID doctorId, int dayOfWeek) {
        return jdbc.query(
                "SELECT id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients " +
                        "FROM doctor_working_hours WHERE doctor_id = ? AND day_of_week = ?",
                workingHoursMapper(), doctorId, dayOfWeek);
    }

    /** The working-hours block ("session") covering a given time-of-day, if any — shared by slot-duration
     *  resolution (AppointmentService) and overbooking-cap resolution (both AppointmentService and QueueService). */
    Optional<WorkingHoursRow> findBlockCovering(UUID doctorId, int dayOfWeek, LocalTime time) {
        return findForDay(doctorId, dayOfWeek).stream()
                .filter(wh -> !time.isBefore(wh.startTime()) && time.isBefore(wh.endTime()))
                .findFirst();
    }

    UUID insertWorkingHours(UUID tenantId, UUID doctorId, int dayOfWeek, LocalTime start,
                             LocalTime end, int slotMinutes, Integer maxPatients) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                id, tenantId, doctorId, dayOfWeek, Time.valueOf(start), Time.valueOf(end), slotMinutes, maxPatients);
        return id;
    }

    List<DoctorLeaveRow> listLeave(UUID doctorId) {
        return jdbc.query(
                "SELECT id, doctor_id, date_from, date_to, reason FROM doctor_leave WHERE doctor_id = ? ORDER BY date_from",
                leaveMapper(), doctorId);
    }

    boolean isOnLeave(UUID doctorId, LocalDate date) {
        Boolean onLeave = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM doctor_leave WHERE doctor_id = ? AND ? BETWEEN date_from AND date_to)",
                Boolean.class, doctorId, Date.valueOf(date));
        return Boolean.TRUE.equals(onLeave);
    }

    UUID insertLeave(UUID tenantId, UUID doctorId, LocalDate from, LocalDate to, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doctor_leave (id, tenant_id, doctor_id, date_from, date_to, reason) VALUES (?,?,?,?,?,?)",
                id, tenantId, doctorId, Date.valueOf(from), Date.valueOf(to), reason);
        return id;
    }

    /** Start times already booked (status='scheduled') for a doctor on a given date — used to exclude taken slots. */
    List<Instant> bookedStartTimes(UUID doctorId, Instant dayStart, Instant dayEnd) {
        return jdbc.query(
                "SELECT start_time FROM appointments " +
                        "WHERE doctor_id = ? AND status = 'scheduled' AND start_time >= ? AND start_time < ?",
                (rs, i) -> rs.getTimestamp("start_time").toInstant(), doctorId,
                Timestamp.from(dayStart), Timestamp.from(dayEnd));
    }

    /**
     * NB-098: scheduled appointments and walk-ins share one cap per session. Walk-ins have no
     * explicit session reference, so they're bucketed by their check-in time-of-day (created_at)
     * falling inside the block — the same window a scheduled appointment's start_time is compared
     * against. AT TIME ZONE 'UTC' forces the comparison into UTC regardless of the DB server's
     * session timezone, matching the UTC assumption the whole slot generator already makes.
     */
    int countSessionOccupancy(UUID doctorId, LocalDate date, LocalTime blockStart, LocalTime blockEnd) {
        Instant rangeStart = date.atTime(blockStart).atZone(ZoneOffset.UTC).toInstant();
        Instant rangeEnd = date.atTime(blockEnd).atZone(ZoneOffset.UTC).toInstant();
        Integer count = jdbc.queryForObject(
                "SELECT " +
                        "(SELECT count(*) FROM appointments a WHERE a.doctor_id = ? AND a.status = 'scheduled' " +
                        "  AND a.start_time >= ? AND a.start_time < ?) " +
                        "+ " +
                        "(SELECT count(*) FROM queue_entries q WHERE q.doctor_id = ? AND q.queue_date = ? " +
                        "  AND q.appointment_id IS NULL AND q.status != 'no_show' " +
                        "  AND (q.created_at AT TIME ZONE 'UTC')::time >= ? AND (q.created_at AT TIME ZONE 'UTC')::time < ?)",
                Integer.class,
                doctorId, Timestamp.from(rangeStart), Timestamp.from(rangeEnd),
                doctorId, Date.valueOf(date), Time.valueOf(blockStart), Time.valueOf(blockEnd));
        return count == null ? 0 : count;
    }

    private RowMapper<WorkingHoursRow> workingHoursMapper() {
        return (rs, i) -> {
            // wasNull() reflects the *last* getXxx() call, not necessarily max_patients — and
            // constructor arguments evaluate left-to-right, so calling it inline as the final
            // argument below was actually checking slot_minutes (always non-null), silently
            // turning every uncapped (NULL) session into maxPatients=0 and blocking all bookings.
            int maxPatients = rs.getInt("max_patients");
            boolean maxPatientsIsNull = rs.wasNull();
            return new WorkingHoursRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("doctor_id")),
                    rs.getInt("day_of_week"),
                    rs.getTime("start_time").toLocalTime(),
                    rs.getTime("end_time").toLocalTime(),
                    rs.getInt("slot_minutes"),
                    maxPatientsIsNull ? null : maxPatients);
        };
    }

    private RowMapper<DoctorLeaveRow> leaveMapper() {
        return (rs, i) -> new DoctorLeaveRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getDate("date_from").toLocalDate(),
                rs.getDate("date_to").toLocalDate(),
                rs.getString("reason"));
    }
}
