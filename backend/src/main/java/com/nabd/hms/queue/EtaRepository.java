package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ACC-017: the reads of the ETA engine for one doctor-day. Ids, statuses and times only. Callers run inside a tenant transaction. */
@Repository
class EtaRepository {

    /** A checked-in visit not yet finished with the doctor; startedAt is set only while in_consult. */
    record Entry(UUID id, UUID appointmentId, String status, boolean priority, Instant createdAt, String visitType, String specialty, Instant startedAt) {
    }

    record Booked(UUID appointmentId, Instant startTime, boolean followUp) {
    }

    private final JdbcTemplate jdbc;

    EtaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Entry> entries(UUID tenantId, UUID doctorId, LocalDate day) {
        return jdbc.query("""
                SELECT q.id, q.appointment_id, q.status, q.priority, q.created_at,
                       CASE WHEN q.class = 'procedure' THEN 'procedure' WHEN a.is_follow_up THEN 'follow_up' ELSE 'new' END AS vt,
                       coalesce(d.specialty_code, 'default') AS specialty,
                       CASE WHEN q.status = 'in_consult' THEN (SELECT max(e.occurred_at) FROM queue_entry_events e
                            WHERE e.queue_entry_id = q.id AND e.to_status = 'in_consult' AND e.kind = 'transition') END AS started_at
                FROM queue_entries q LEFT JOIN appointments a ON a.id = q.appointment_id LEFT JOIN departments d ON d.id = q.department_id
                WHERE q.tenant_id = ? AND q.doctor_id = ? AND q.queue_date = ?
                  AND q.status IN ('checked_in','waiting','billing_pending','vitals_pending','vitals_done','in_consult')
                ORDER BY q.priority DESC, q.created_at, q.id""",
                (rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class), rs.getString("status"),
                        rs.getBoolean("priority"), rs.getTimestamp("created_at").toInstant(), rs.getString("vt"), rs.getString("specialty"),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant()),
                tenantId, doctorId, java.sql.Date.valueOf(day));
    }

    /** Booked for the clinic day, not yet checked in. */
    List<Booked> bookedNotArrived(UUID tenantId, UUID doctorId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT a.id, a.start_time, a.is_follow_up FROM appointments a
                WHERE a.tenant_id = ? AND a.doctor_id = ? AND a.status = 'scheduled' AND a.start_time >= ? AND a.start_time < ?
                  AND NOT EXISTS (SELECT 1 FROM queue_entries q WHERE q.appointment_id = a.id)
                ORDER BY a.start_time""",
                (rs, i) -> new Booked(rs.getObject("id", UUID.class), rs.getTimestamp("start_time").toInstant(), rs.getBoolean("is_follow_up")),
                tenantId, doctorId, Timestamp.from(from), Timestamp.from(to));
    }

    int activeDelayMinutes(UUID tenantId, UUID doctorId, LocalDate day) {
        Integer m = jdbc.query("SELECT delay_minutes FROM doctor_delays WHERE tenant_id = ? AND doctor_id = ? AND clinic_date = ? AND cleared_at IS NULL",
                (rs, i) -> rs.getInt(1), tenantId, doctorId, java.sql.Date.valueOf(day)).stream().findFirst().orElse(0);
        return m;
    }

    /** Doctors with anyone waiting or in consult today: the 60 s tick recomputes these (ETAs drift with the clock). */
    List<UUID> activeDoctors(UUID tenantId, LocalDate day) {
        return jdbc.queryForList("SELECT DISTINCT doctor_id FROM queue_entries WHERE tenant_id = ? AND queue_date = ? AND status IN ('checked_in','waiting','billing_pending','vitals_pending','vitals_done','in_consult')",
                UUID.class, tenantId, java.sql.Date.valueOf(day));
    }

    record DoctorDay(UUID doctorId, LocalDate day) {
    }
}
