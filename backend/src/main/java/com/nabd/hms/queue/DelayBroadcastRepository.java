package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-024: delay_broadcast_recipients and the candidate query. Ids, statuses and times only. Callers run inside a tenant transaction. */
@Repository
class DelayBroadcastRepository {

    /** Someone still to be seen by this doctor: booked and not arrived, or checked in and not yet called. */
    record Candidate(UUID appointmentId, UUID queueEntryId, UUID patientId) {
    }

    record Recipient(UUID id, UUID appointmentId, UUID patientId, String status, String response, Instant createdAt, Instant respondedAt) {
    }

    private final JdbcTemplate jdbc;

    DelayBroadcastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Candidate> candidates(UUID tenantId, UUID doctorId, java.time.LocalDate day, Instant from, Instant until) {
        return jdbc.query("""
                SELECT a.id AS appointment_id, NULL::uuid AS queue_entry_id, a.patient_id FROM appointments a
                 WHERE a.tenant_id = ? AND a.doctor_id = ? AND a.status = 'scheduled' AND a.start_time >= ? AND a.start_time < ?
                   AND NOT EXISTS (SELECT 1 FROM queue_entries q WHERE q.appointment_id = a.id)
                UNION ALL
                SELECT q.appointment_id, q.id, q.patient_id FROM queue_entries q
                 WHERE q.tenant_id = ? AND q.doctor_id = ? AND q.queue_date = ?
                   AND q.status IN ('checked_in','waiting','billing_pending','vitals_pending','vitals_done')""",
                (rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)),
                tenantId, doctorId, Timestamp.from(from), Timestamp.from(until), tenantId, doctorId, java.sql.Date.valueOf(day));
    }

    /** True when the patient was messaged (or had one queued) since {@code since}: the 20-minute rule. */
    boolean messagedSince(UUID tenantId, UUID patientId, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM delay_broadcast_recipients WHERE tenant_id = ? AND patient_id = ?
                              AND status IN ('queued','sent','delivered') AND created_at > ?)""",
                Boolean.class, tenantId, patientId, Timestamp.from(since)));
    }

    void insert(UUID tenantId, UUID doctorId, UUID delayId, UUID pauseId, Candidate c, String status, UUID messageRef, Instant expectedAt, Instant now) {
        jdbc.update("""
                INSERT INTO delay_broadcast_recipients (tenant_id, doctor_id, delay_id, pause_id, appointment_id, queue_entry_id, patient_id,
                                                        message_ref, expected_at, status, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                tenantId, doctorId, delayId, pauseId, c.appointmentId(), c.queueEntryId(), c.patientId(),
                messageRef == null ? null : messageRef.toString(), expectedAt == null ? null : Timestamp.from(expectedAt), status, Timestamp.from(now));
    }

    /** What a patient was last told for this pause (the time the resume is compared with). */
    Optional<Instant> expectedAt(UUID tenantId, UUID pauseId, UUID patientId) {
        return jdbc.query("SELECT expected_at FROM delay_broadcast_recipients WHERE tenant_id = ? AND pause_id = ? AND patient_id = ? AND expected_at IS NOT NULL",
                (rs, i) -> rs.getTimestamp(1).toInstant(), tenantId, pauseId, patientId).stream().findFirst();
    }

    boolean delayOf(UUID tenantId, UUID doctorId, UUID delayId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM doctor_delays WHERE tenant_id = ? AND doctor_id = ? AND id = ?)",
                Boolean.class, tenantId, doctorId, delayId));
    }

    List<Recipient> forDelay(UUID tenantId, UUID delayId) {
        return jdbc.query("""
                SELECT id, appointment_id, patient_id, status, response, created_at, responded_at FROM delay_broadcast_recipients
                 WHERE tenant_id = ? AND delay_id = ? ORDER BY created_at, id""",
                (rs, i) -> new Recipient(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                tenantId, delayId);
    }

    /** The patient's newest unanswered delay message of the last day gets the answer; false when there is none. */
    boolean recordReply(UUID tenantId, UUID patientId, String response, Instant now) {
        return jdbc.update("""
                UPDATE delay_broadcast_recipients SET response = ?, responded_at = ? WHERE id = (
                  SELECT id FROM delay_broadcast_recipients WHERE tenant_id = ? AND patient_id = ? AND response IS NULL
                     AND status IN ('queued','sent','delivered') AND created_at > ? ORDER BY created_at DESC LIMIT 1)""",
                response, Timestamp.from(now), tenantId, patientId, Timestamp.from(now.minusSeconds(86400))) == 1;
    }

    /** The newest pause of a session (the open one on pause, the one just closed on resume). */
    Optional<UUID> latestPause(UUID tenantId, UUID sessionId) {
        return jdbc.query("SELECT id FROM doctor_session_pauses WHERE tenant_id = ? AND session_id = ? ORDER BY paused_at DESC LIMIT 1",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, sessionId).stream().findFirst();
    }
}
