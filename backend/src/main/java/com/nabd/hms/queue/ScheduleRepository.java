package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.DoctorDelayRow;
import static com.nabd.hms.queue.QueueModels.DoctorLeaveRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Repository
class ScheduleRepository {

    private final JdbcTemplate jdbc;
    private final ClinicClock clock;

    ScheduleRepository(JdbcTemplate jdbc, ClinicClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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

    private static final String WH_COLS = "id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients, " +
            "session_mode, previous_session_mode, online_cap, label, effective_from, walkin_mode, walkin_ratio, late_grace_minutes ";

    List<WorkingHoursRow> listWorkingHours(UUID doctorId) {
        return jdbc.query(
                "SELECT " + WH_COLS +
                        "FROM doctor_working_hours WHERE doctor_id = ? ORDER BY day_of_week, start_time",
                workingHoursMapper(), doctorId);
    }

    /** ACC-038: every doctor of the tenant that has working hours (the Edge availability projection). */
    List<UUID> doctorsWithHours(UUID tenantId) {
        return jdbc.query("SELECT DISTINCT doctor_id FROM doctor_working_hours WHERE tenant_id = ? ORDER BY doctor_id",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId);
    }

    List<WorkingHoursRow> findForDay(UUID doctorId, int dayOfWeek) {
        return jdbc.query(
                "SELECT " + WH_COLS +
                        "FROM doctor_working_hours WHERE doctor_id = ? AND day_of_week = ?",
                workingHoursMapper(), doctorId, dayOfWeek);
    }

    /**
     * The working-hours block ("session") covering an instant, if any: shared by slot-duration resolution and cap resolution.
     * ACC-005: looked up on the instant's CLINIC date (a 00:30 instant belongs to the evening block that began the day before),
     * so an overnight block covers seconds up to 86400 + its end.
     */
    Optional<WorkingHoursRow> blockAt(UUID tenantId, UUID doctorId, Instant at) {
        LocalDate date = clock.clinicDate(tenantId, at);
        int second = clock.secondOfClinicDay(tenantId, date, at);
        return findForDay(doctorId, date.getDayOfWeek().getValue() % 7).stream()
                .filter(wh -> second >= wh.startSecond() && second < wh.endSecond())
                .findFirst();
    }

    /** ACC-005: overnight blocks that would end after a (new) day start. */
    int overnightEndingAfter(UUID tenantId, int daySecond) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM doctor_working_hours WHERE tenant_id = ? AND end_time < start_time " +
                "AND extract(epoch FROM end_time) > ?", Integer.class, tenantId, daySecond);
        return n == null ? 0 : n;
    }

    void setDayStart(UUID tenantId, int minute) {
        jdbc.update("UPDATE tenants SET day_start_minute = ?, updated_at = now() WHERE id = ?", minute, tenantId);
    }

    UUID insertWorkingHours(UUID tenantId, UUID doctorId, int dayOfWeek, LocalTime start,
                             LocalTime end, int slotMinutes, Integer maxPatients, String sessionMode, String previousSessionMode,
                             Integer onlineCap, String label, LocalDate effectiveFrom) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients, " +
                        "session_mode, previous_session_mode, online_cap, label, effective_from) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, doctorId, dayOfWeek, Time.valueOf(start), Time.valueOf(end), slotMinutes, maxPatients,
                sessionMode, previousSessionMode, onlineCap, label, effectiveFrom == null ? null : Date.valueOf(effectiveFrom));
        return id;
    }

    Optional<WorkingHoursRow> findWorkingHours(UUID doctorId, UUID hoursId) {
        return jdbc.query(
                "SELECT " + WH_COLS +
                        "FROM doctor_working_hours WHERE doctor_id = ? AND id = ?",
                workingHoursMapper(), doctorId, hoursId).stream().findFirst();
    }

    /** DOC-R7: serialises hour writes per doctor so two concurrent writes can't both pass the overlap check. */
    void lockDoctorHours(UUID doctorId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "working-hours:" + doctorId);
    }

    /** Half-open blocks: 09:00-12:00 and 12:00-14:00 touch but don't overlap. excludeId = the row being updated. ACC-005: overnight blocks count to 24:00 + their end. */
    boolean hoursOverlap(UUID doctorId, int dayOfWeek, LocalTime start, LocalTime end, UUID excludeId) {
        int s = start.toSecondOfDay();
        int e = end.toSecondOfDay() + (end.isAfter(start) ? 0 : 86400);
        return findForDay(doctorId, dayOfWeek).stream()
                .anyMatch(wh -> !wh.id().equals(excludeId) && wh.startSecond() < e && wh.endSecond() > s);
    }

    void updateWorkingHours(UUID doctorId, UUID hoursId, int dayOfWeek, LocalTime start, LocalTime end,
                            int slotMinutes, Integer maxPatients, String sessionMode, String previousSessionMode,
                            Integer onlineCap, String label, LocalDate effectiveFrom) {
        jdbc.update("UPDATE doctor_working_hours SET day_of_week = ?, start_time = ?, end_time = ?, slot_minutes = ?, " +
                        "max_patients = ?, session_mode = ?, previous_session_mode = ?, online_cap = ?, label = ?, effective_from = ? " +
                        "WHERE doctor_id = ? AND id = ?",
                dayOfWeek, Time.valueOf(start), Time.valueOf(end), slotMinutes, maxPatients, sessionMode, previousSessionMode,
                onlineCap, label, effectiveFrom == null ? null : Date.valueOf(effectiveFrom), doctorId, hoursId);
    }

    void deleteWorkingHours(UUID doctorId, UUID hoursId) {
        jdbc.update("DELETE FROM doctor_working_hours WHERE doctor_id = ? AND id = ?", doctorId, hoursId);
    }

    List<DoctorLeaveRow> listLeave(UUID doctorId) {
        return jdbc.query(
                "SELECT id, doctor_id, date_from, date_to, reason, start_time, end_time FROM doctor_leave WHERE doctor_id = ? ORDER BY date_from",
                leaveMapper(), doctorId);
    }

    /** A whole-day leave covers the date; part-day leave is {@link #partialLeaves}. */
    boolean isOnLeave(UUID doctorId, LocalDate date) {
        Boolean onLeave = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM doctor_leave WHERE doctor_id = ? AND ? BETWEEN date_from AND date_to AND start_time IS NULL)",
                Boolean.class, doctorId, Date.valueOf(date));
        return Boolean.TRUE.equals(onLeave);
    }

    /** ACC-005: part-day leave windows on a clinic date, as [startSecond, endSecond) from the date's local midnight. */
    List<int[]> partialLeaves(UUID tenantId, UUID doctorId, LocalDate date) {
        int dayStartSecond = clock.dayStartMinute(tenantId) * 60;
        return jdbc.query(
                "SELECT id, doctor_id, date_from, date_to, reason, start_time, end_time FROM doctor_leave " +
                        "WHERE doctor_id = ? AND ? BETWEEN date_from AND date_to AND start_time IS NOT NULL",
                leaveMapper(), doctorId, Date.valueOf(date)).stream().map(l -> l.window(dayStartSecond)).toList();
    }

    /** The slot [startSecond, startSecond + slotSeconds) touches a leave window. */
    static boolean overlapsLeave(List<int[]> windows, int startSecond, int slotSeconds) {
        return windows.stream().anyMatch(w -> startSecond < w[1] && startSecond + slotSeconds > w[0]);
    }

    UUID insertLeave(UUID tenantId, UUID doctorId, LocalDate from, LocalDate to, String reason, LocalTime start, LocalTime end) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doctor_leave (id, tenant_id, doctor_id, date_from, date_to, reason, start_time, end_time) VALUES (?,?,?,?,?,?,?,?)",
                id, tenantId, doctorId, Date.valueOf(from), Date.valueOf(to), reason,
                start == null ? null : Time.valueOf(start), end == null ? null : Time.valueOf(end));
        return id;
    }

    /** ACC-006: marks the bookings a leave block covers, so the desk can tag them until they are moved. */
    void flagNeedsReschedule(UUID tenantId, List<UUID> appointmentIds, String reason) {
        if (appointmentIds.isEmpty()) return;
        jdbc.update("UPDATE appointments SET needs_reschedule_reason = ? WHERE tenant_id = ? AND status IN ('requested','scheduled') AND id = ANY(?::uuid[])",
                reason, tenantId, "{" + String.join(",", appointmentIds.stream().map(UUID::toString).toList()) + "}");
    }

    /** ACC-005: live bookings of the doctor starting in [from, to); the caller narrows to a part-day window. */
    List<Object[]> liveBookingsBetween(UUID tenantId, UUID doctorId, Instant from, Instant to) {
        return jdbc.query(
                "SELECT id, start_time FROM appointments WHERE tenant_id = ? AND doctor_id = ? AND status IN ('requested','scheduled') " +
                        "AND start_time >= ? AND start_time < ? ORDER BY start_time",
                (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getTimestamp("start_time").toInstant()},
                tenantId, doctorId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * NB-091: "blocking a doctor warns about affected package sessions restricted to that doctor" —
     * a narrow direct query into the packages module's tables (same convention as CheckoutRepository
     * reading pharmacy/procedure_orders directly) rather than injecting PackageRepository. Active
     * instances only, and only packages actually restricted to a doctor via package_eligible_doctors
     * (an unrestricted package is never "affected" by one doctor's leave), with at least one
     * item still unconsumed.
     */
    List<String> findAffectedPackagePatientNames(UUID tenantId, UUID doctorId) {
        return jdbc.query(
                "SELECT DISTINCT p.name || ' (' || pi.package_name || ')' AS label " +
                        "FROM package_instances pi " +
                        "JOIN patients p ON p.id = pi.patient_id " +
                        "JOIN package_eligible_doctors ed ON ed.package_id = pi.package_id AND ed.doctor_id = ? " +
                        "WHERE pi.tenant_id = ? AND pi.status = 'active' " +
                        "AND EXISTS (SELECT 1 FROM package_instance_items i WHERE i.instance_id = pi.id AND i.quantity_consumed < i.quantity_total)",
                (rs, i) -> rs.getString("label"), doctorId, tenantId);
    }

    /** Start times already booked (requested, scheduled, arrived) for a doctor on a given date — used to exclude taken slots. */
    List<Instant> bookedStartTimes(UUID doctorId, Instant dayStart, Instant dayEnd) {
        return jdbc.query(
                "SELECT start_time FROM appointments " +
                        "WHERE doctor_id = ? AND status IN ('requested','scheduled','arrived') AND start_time >= ? AND start_time < ?",
                (rs, i) -> rs.getTimestamp("start_time").toInstant(), doctorId,
                Timestamp.from(dayStart), Timestamp.from(dayEnd));
    }

    /**
     * NB-098: scheduled appointments and walk-ins share one cap per session. Walk-ins have no explicit session reference, so they're
     * bucketed by their check-in instant (created_at) falling inside the block's window on the CLINIC date (ACC-005: an overnight
     * block's window runs past midnight, so a 00:30 walk-in still counts against the evening session).
     */
    int countSessionOccupancy(UUID tenantId, UUID doctorId, LocalDate date, WorkingHoursRow wh) {
        Timestamp rangeStart = Timestamp.from(clock.atSecond(tenantId, date, wh.startSecond()));
        Timestamp rangeEnd = Timestamp.from(clock.atSecond(tenantId, date, wh.endSecond()));
        Integer count = jdbc.queryForObject(
                "SELECT " +
                        // QA-04: a seen booking (completed) still used its place, as a completed walk-in does
                        "(SELECT count(*) FROM appointments a WHERE a.tenant_id = ? AND a.doctor_id = ? AND a.status IN ('requested','scheduled','arrived','completed') " +
                        "  AND a.start_time >= ? AND a.start_time < ?) " +
                        "+ " +
                        "(SELECT count(*) FROM queue_entries q WHERE q.tenant_id = ? AND q.doctor_id = ? AND q.queue_date = ? " +
                        "  AND q.appointment_id IS NULL AND q.status != 'no_show' " +
                        "  AND q.created_at >= ? AND q.created_at < ?)",
                Integer.class,
                tenantId, doctorId, rangeStart, rangeEnd,
                tenantId, doctorId, Date.valueOf(date), rangeStart, rangeEnd);
        return count == null ? 0 : count;
    }

    /** ACC-011: the bookings (appointments) of the session; the rest of countSessionOccupancy is desk walk-ins. Online cap counts these. */
    int countSessionBookings(UUID tenantId, UUID doctorId, LocalDate date, WorkingHoursRow wh) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM appointments a WHERE a.tenant_id = ? AND a.doctor_id = ? " +
                        "AND a.status IN ('scheduled','arrived','completed') AND a.start_time >= ? AND a.start_time < ?",
                Integer.class, tenantId, doctorId, Timestamp.from(clock.atSecond(tenantId, date, wh.startSecond())),
                Timestamp.from(clock.atSecond(tenantId, date, wh.endSecond())));
        return count == null ? 0 : count;
    }

    /** ACC-011: lowest token 1..max not held by a live token booking of this session and date; empty = every place is taken. */
    Optional<Integer> lowestFreeToken(UUID tenantId, UUID doctorId, UUID workingHoursId, Instant sessionStart, int max) {
        return jdbc.query("SELECT n FROM generate_series(1, ?) n WHERE NOT EXISTS (SELECT 1 FROM appointments a " +
                        "WHERE a.tenant_id = ? AND a.doctor_id = ? AND a.working_hours_id = ? AND a.start_time = ? " +
                        "AND a.booking_kind = 'token' AND a.status IN ('scheduled','arrived') AND a.session_token = n) ORDER BY n LIMIT 1",
                (rs, i) -> rs.getInt(1), max, tenantId, doctorId, workingHoursId, Timestamp.from(sessionStart)).stream().findFirst();
    }

    /** ACC-012: slot minutes-of-day held back for walk-ins in this session. */
    java.util.Set<Integer> reservedMinutes(UUID workingHoursId) {
        return new java.util.HashSet<>(jdbc.query("SELECT slot_minute FROM session_reserved_slots WHERE working_hours_id = ?",
                (rs, i) -> rs.getInt(1), workingHoursId));
    }

    void updateWalkinRules(UUID doctorId, UUID hoursId, String mode, Integer ratio, Integer graceMinutes) {
        jdbc.update("UPDATE doctor_working_hours SET walkin_mode = COALESCE(?, walkin_mode), walkin_ratio = COALESCE(?, walkin_ratio), " +
                "late_grace_minutes = COALESCE(?, late_grace_minutes) WHERE doctor_id = ? AND id = ?", mode, ratio, graceMinutes, doctorId, hoursId);
    }

    void replaceReservedMinutes(UUID tenantId, UUID workingHoursId, java.util.Collection<Integer> minutes) {
        jdbc.update("DELETE FROM session_reserved_slots WHERE working_hours_id = ?", workingHoursId);
        for (int m : minutes) {
            jdbc.update("INSERT INTO session_reserved_slots (tenant_id, working_hours_id, slot_minute) VALUES (?,?,?)",
                    tenantId, workingHoursId, m);
        }
    }

    // ── delay ladder (NB-100) ────────────────────────────────────────────

    private static final String DELAY_SELECT =
            "SELECT id, doctor_id, delay_minutes, reason, announced_by, announced_at, cleared_by, cleared_at, clinic_date " +
                    "FROM doctor_delays WHERE doctor_id = ? ";

    Optional<DoctorDelayRow> findActiveDelay(UUID doctorId, java.time.LocalDate today) {
        return jdbc.query(DELAY_SELECT + "AND cleared_at IS NULL AND clinic_date = ?", delayMapper(), doctorId, today).stream().findFirst();
    }

    List<DoctorDelayRow> delayHistory(UUID doctorId, int limit) {
        return jdbc.query(DELAY_SELECT + "ORDER BY announced_at DESC LIMIT ?", delayMapper(), doctorId, limit);
    }

    /** "Writing the delay then closing and appending to history" — a fresh announcement always
     * closes out any still-active one first, so the unique active-per-doctor index never fires. */
    UUID announceDelay(UUID tenantId, UUID doctorId, int delayMinutes, String reason, UUID announcedBy, java.time.LocalDate clinicDate) {
        // A concurrent announcement for the same doctor loses (409); sequential ones still supersede.
        // Also lost if another announcement was inserted after this transaction began (shortcut: requests staggered beyond a txn are indistinguishable from fast sequential supersedes, which must stay 201).
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, "doctor-delay:" + doctorId))
                || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM doctor_delays WHERE doctor_id = ? AND cleared_at IS NULL AND announced_at >= transaction_timestamp())", Boolean.class, doctorId))) {
            throw new DuplicateKeyException("delay announcement in flight");
        }
        clearActiveDelay(tenantId, doctorId, announcedBy);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doctor_delays (id, tenant_id, doctor_id, delay_minutes, reason, announced_by, clinic_date, announced_at) VALUES (?,?,?,?,?,?,?, clock_timestamp())",
                id, tenantId, doctorId, delayMinutes, reason, announcedBy, clinicDate);
        return id;
    }

    boolean doctorInTenant(UUID tenantId, UUID doctorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM staff WHERE tenant_id = ? AND id = ?)", Boolean.class, tenantId, doctorId));
    }

    void clearActiveDelay(UUID tenantId, UUID doctorId, UUID clearedBy) {
        jdbc.update("UPDATE doctor_delays SET cleared_at = now(), cleared_by = ? " +
                "WHERE tenant_id = ? AND doctor_id = ? AND cleared_at IS NULL", clearedBy, tenantId, doctorId);
    }

    private RowMapper<DoctorDelayRow> delayMapper() {
        return (rs, i) -> {
            Timestamp clearedAt = rs.getTimestamp("cleared_at");
            String clearedBy = rs.getString("cleared_by");
            return new DoctorDelayRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("doctor_id")),
                    rs.getInt("delay_minutes"),
                    rs.getString("reason"),
                    UUID.fromString(rs.getString("announced_by")),
                    rs.getTimestamp("announced_at").toInstant(),
                    clearedBy == null ? null : UUID.fromString(clearedBy),
                    clearedAt == null ? null : clearedAt.toInstant(),
                    rs.getObject("clinic_date", java.time.LocalDate.class));
        };
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
                    maxPatientsIsNull ? null : maxPatients,
                    rs.getString("session_mode"), rs.getString("previous_session_mode"),
                    (Integer) rs.getObject("online_cap"), rs.getString("label"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getString("walkin_mode"), rs.getInt("walkin_ratio"), rs.getInt("late_grace_minutes"));
        };
    }

    private RowMapper<DoctorLeaveRow> leaveMapper() {
        return (rs, i) -> new DoctorLeaveRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getDate("date_from").toLocalDate(),
                rs.getDate("date_to").toLocalDate(),
                rs.getString("reason"),
                rs.getTime("start_time") == null ? null : rs.getTime("start_time").toLocalTime(),
                rs.getTime("end_time") == null ? null : rs.getTime("end_time").toLocalTime());
    }
}
