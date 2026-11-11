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

    /** Idempotent: the first confirmation time stays; a second tap returns the same instant. COM-043: the reminder cascade stops. */
    Instant confirm(UUID tenantId, UUID appointmentId) {
        return jdbc.queryForObject("UPDATE appointments SET patient_confirmed_at = COALESCE(patient_confirmed_at, now()), confirmation_state = 'confirmed' "
                + "WHERE tenant_id = ? AND id = ? RETURNING patient_confirmed_at", java.sql.Timestamp.class, tenantId, appointmentId).toInstant();
    }

    void callMe(UUID tenantId, UUID appointmentId) {
        jdbc.update("INSERT INTO appointment_desk_exceptions (tenant_id, appointment_id, kind) VALUES (?, ?, 'call_me') "
                + "ON CONFLICT (tenant_id, appointment_id, kind) DO UPDATE SET status = 'open'", tenantId, appointmentId);
    }
}
