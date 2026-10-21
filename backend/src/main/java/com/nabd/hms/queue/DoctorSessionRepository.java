package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** ACC-015: doctor_sessions and doctor_session_pauses. */
@Repository
class DoctorSessionRepository {

    record Session(UUID id, String state, Instant since) {
    }

    private final JdbcTemplate jdbc;

    DoctorSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when a session was opened; false when the doctor already has an open one today (unique partial index). */
    boolean open(UUID tenantId, UUID doctorId, LocalDate day, Instant at) {
        return jdbc.update("INSERT INTO doctor_sessions (tenant_id, doctor_id, clinic_date, state, started_at) VALUES (?,?,?,'in',?) " +
                        "ON CONFLICT (tenant_id, doctor_id, clinic_date) WHERE state <> 'out' DO NOTHING",
                tenantId, doctorId, Date.valueOf(day), Timestamp.from(at)) == 1;
    }

    /** The doctor's open (in or paused) session, row-locked so two taps cannot both pass the state check. */
    Optional<Session> lockOpen(UUID tenantId, UUID doctorId, LocalDate day) {
        return jdbc.query("SELECT id, state, started_at FROM doctor_sessions WHERE tenant_id = ? AND doctor_id = ? " +
                        "AND clinic_date = ? AND state <> 'out' FOR UPDATE",
                (rs, i) -> new Session(rs.getObject("id", UUID.class), rs.getString("state"), rs.getTimestamp("started_at").toInstant()),
                tenantId, doctorId, Date.valueOf(day)).stream().findFirst();
    }

    void pause(UUID tenantId, UUID sessionId, Instant at, String reasonCode) {
        jdbc.update("UPDATE doctor_sessions SET state = 'paused' WHERE tenant_id = ? AND id = ?", tenantId, sessionId);
        jdbc.update("INSERT INTO doctor_session_pauses (tenant_id, session_id, paused_at, reason_code) VALUES (?,?,?,?)",
                tenantId, sessionId, Timestamp.from(at), reasonCode);
    }

    void resume(UUID tenantId, UUID sessionId, Instant at) {
        jdbc.update("UPDATE doctor_sessions SET state = 'in' WHERE tenant_id = ? AND id = ?", tenantId, sessionId);
        closePause(tenantId, sessionId, at);
    }

    void end(UUID tenantId, UUID sessionId, Instant at) {
        closePause(tenantId, sessionId, at);
        jdbc.update("UPDATE doctor_sessions SET state = 'out', ended_at = ? WHERE tenant_id = ? AND id = ?", Timestamp.from(at), tenantId, sessionId);
    }

    private void closePause(UUID tenantId, UUID sessionId, Instant at) {
        jdbc.update("UPDATE doctor_session_pauses SET resumed_at = ? WHERE tenant_id = ? AND session_id = ? AND resumed_at IS NULL",
                Timestamp.from(at), tenantId, sessionId);
    }

    /** The doctor's latest session of the day and when it last changed state. */
    Optional<Session> latest(UUID tenantId, UUID doctorId, LocalDate day) {
        return jdbc.query("SELECT s.id, s.state, CASE s.state WHEN 'out' THEN s.ended_at " +
                        "WHEN 'paused' THEN (SELECT max(paused_at) FROM doctor_session_pauses p WHERE p.session_id = s.id) " +
                        "ELSE GREATEST(s.started_at, (SELECT max(resumed_at) FROM doctor_session_pauses p WHERE p.session_id = s.id)) END AS since " +
                        "FROM doctor_sessions s WHERE s.tenant_id = ? AND s.doctor_id = ? AND s.clinic_date = ? " +
                        "ORDER BY s.started_at DESC, s.id LIMIT 1",
                (rs, i) -> new Session(rs.getObject("id", UUID.class), rs.getString("state"), rs.getTimestamp("since").toInstant()),
                tenantId, doctorId, Date.valueOf(day)).stream().findFirst();
    }
}
