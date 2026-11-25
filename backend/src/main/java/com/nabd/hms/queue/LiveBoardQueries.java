package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.queue.api.LiveBoard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-042: queue.api.LiveBoard. Six small statements, each on one clinic day of one tenant (sessions, visits, bookings, announced
 * delays, working hours, delay messages); the minutes are worked out here so the lateness rule lives with the scheduling module.
 * shortcut: one session per doctor-day (first start to last end, as ADM-041's punctuality), a doctor who is in session but behind
 * schedule counts as late only once a delay is announced; per-block lateness needs the ETA projection when owners ask for it.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class LiveBoardQueries implements LiveBoard {

    private static final String WAITING = "('checked_in','waiting','billing_pending','vitals_pending','vitals_done')";

    private record Visits(int waiting, Instant since, boolean called) {
    }

    private record Delay(UUID id, int minutes) {
    }

    private final JdbcTemplate jdbc;
    private final ClinicClock clock;

    LiveBoardQueries(JdbcTemplate jdbc, ClinicClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Doctor> doctors(UUID tenantId, Instant now) {
        LocalDate day = clock.clinicDate(tenantId, now);
        Date d = Date.valueOf(day);
        Instant dayStart = clock.dayStart(tenantId, day);
        Instant dayEnd = clock.dayStart(tenantId, day.plusDays(1));

        Map<UUID, String> sessions = new HashMap<>();
        jdbc.query("SELECT DISTINCT ON (doctor_id) doctor_id, state FROM doctor_sessions WHERE tenant_id = ? AND clinic_date = ? "
                + "ORDER BY doctor_id, started_at DESC", rs -> {
            sessions.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, tenantId, d);
        Map<UUID, Visits> visits = new HashMap<>();
        jdbc.query("SELECT doctor_id, count(*) FILTER (WHERE status IN " + WAITING + "), min(created_at) FILTER (WHERE status IN " + WAITING + "), "
                + "COALESCE(bool_or(status IN ('in_consult','checkout_pending','completed')), false) "
                + "FROM queue_entries WHERE tenant_id = ? AND queue_date = ? AND doctor_id IS NOT NULL GROUP BY doctor_id", rs -> {
            visits.put(rs.getObject(1, UUID.class), new Visits(rs.getInt(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getBoolean(4)));
        }, tenantId, d);
        Map<UUID, Integer> booked = new HashMap<>();
        jdbc.query("SELECT a.doctor_id, count(*) FROM appointments a WHERE a.tenant_id = ? AND a.status = 'scheduled' AND a.start_time >= ? AND a.start_time < ? "
                + "AND NOT EXISTS (SELECT 1 FROM queue_entries q WHERE q.appointment_id = a.id) GROUP BY a.doctor_id", rs -> {
            booked.put(rs.getObject(1, UUID.class), rs.getInt(2));
        }, tenantId, Timestamp.from(dayStart), Timestamp.from(dayEnd));
        Map<UUID, Delay> delays = new HashMap<>();
        jdbc.query("SELECT doctor_id, id, delay_minutes FROM doctor_delays WHERE tenant_id = ? AND clinic_date = ? AND cleared_at IS NULL AND expires_at > ?", rs -> {
            delays.put(rs.getObject(1, UUID.class), new Delay(rs.getObject(2, UUID.class), rs.getInt(3)));
        }, tenantId, d, Timestamp.from(now));

        Set<UUID> ids = new LinkedHashSet<>(sessions.keySet());
        ids.addAll(visits.keySet());
        ids.addAll(booked.keySet());
        ids.addAll(delays.keySet());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, LocalTime[]> hours = new HashMap<>(); // a doctor on whole-day leave has no session to be late for
        jdbc.query("SELECT h.doctor_id, min(h.start_time), max(h.end_time) FROM doctor_working_hours h WHERE h.tenant_id = ? AND h.day_of_week = ? "
                + "AND NOT EXISTS (SELECT 1 FROM doctor_leave l WHERE l.tenant_id = h.tenant_id AND l.doctor_id = h.doctor_id "
                + "AND ? BETWEEN l.date_from AND l.date_to AND l.start_time IS NULL) GROUP BY h.doctor_id", rs -> {
            hours.put(rs.getObject(1, UUID.class), new LocalTime[] {rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class)});
        }, tenantId, day.getDayOfWeek().getValue() % 7, d);
        Map<UUID, Integer> told = new HashMap<>();
        jdbc.query("SELECT doctor_id, count(DISTINCT patient_id) FROM delay_broadcast_recipients WHERE tenant_id = ? AND created_at >= ? "
                + "AND status IN ('queued','sent','delivered') GROUP BY doctor_id", rs -> {
            told.put(rs.getObject(1, UUID.class), rs.getInt(2));
        }, tenantId, Timestamp.from(dayStart));

        var local = now.atZone(clock.zone(tenantId)); // after midnight of a late clinic day the wall clock no longer compares with that day's hours
        LocalTime nowLocal = local.toLocalDate().equals(day) ? local.toLocalTime() : null;
        List<Doctor> out = new ArrayList<>();
        for (UUID id : ids) {
            Visits v = visits.getOrDefault(id, new Visits(0, null, false));
            Delay delay = delays.get(id);
            LocalTime[] h = hours.get(id);
            boolean started = sessions.containsKey(id) || v.called();
            out.add(new Doctor(id, sessions.getOrDefault(id, "not_started"), v.waiting(), v.since(), booked.getOrDefault(id, 0),
                    lateMinutes(delay == null ? 0 : delay.minutes(), started, h == null ? null : h[0], h == null ? null : h[1], nowLocal),
                    delay == null ? null : delay.id(), told.getOrDefault(id, 0)));
        }
        return out;
    }

    /** The announced delay, or the minutes since the day's first session start while nothing has started and the last session has not ended. */
    static int lateMinutes(int announced, boolean started, LocalTime firstStart, LocalTime lastEnd, LocalTime now) {
        boolean overdue = !started && firstStart != null && now != null && !now.isBefore(firstStart) && now.isBefore(lastEnd);
        return Math.max(announced, overdue ? (int) Duration.between(firstStart, now).toMinutes() : 0);
    }

    @Override
    public List<OpenRequest> openRequests(UUID tenantId, int limit) {
        return jdbc.query("SELECT a.id, a.created_at FROM appointments a JOIN booking_request_details d ON d.appointment_id = a.id "
                        + "WHERE a.tenant_id = ? AND a.status = 'requested' ORDER BY a.created_at, a.id LIMIT ?",
                (rs, i) -> new OpenRequest(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()), tenantId, limit);
    }
}
