package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/** ACC-041: the patient's confirmation of an appointment and the desk exception behind a "Call me" request. */
@Repository
class PatientConfirmationRepository {

    private final JdbcTemplate jdbc;

    PatientConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Confirmation(Instant at, boolean first) {
    }

    /** Idempotent: the first confirmation time stays; a second tap returns the same instant with first = false (decided by the
     * conditional UPDATE, so two concurrent taps cannot both be first). COM-043: the reminder cascade stops. */
    Confirmation confirm(UUID tenantId, UUID appointmentId) {
        boolean first = jdbc.update("UPDATE appointments SET patient_confirmed_at = now(), confirmation_state = 'confirmed' "
                + "WHERE tenant_id = ? AND id = ? AND patient_confirmed_at IS NULL", tenantId, appointmentId) == 1;
        Instant at = jdbc.queryForObject("SELECT patient_confirmed_at FROM appointments WHERE tenant_id = ? AND id = ?",
                java.sql.Timestamp.class, tenantId, appointmentId).toInstant();
        return new Confirmation(at, first);
    }

    void callMe(UUID tenantId, UUID appointmentId) {
        jdbc.update("INSERT INTO appointment_desk_exceptions (tenant_id, appointment_id, kind) VALUES (?, ?, 'call_me') "
                + "ON CONFLICT (tenant_id, appointment_id, kind) DO UPDATE SET status = 'open'", tenantId, appointmentId);
    }
}
