package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-018: doctor_queue_settings and the session override column on doctor_working_hours. */
@Repository
class EtaDisplayRepository {

    record SessionMode(UUID workingHoursId, int dayOfWeek, LocalTime startTime, LocalTime endTime, String mode) {
    }

    private final JdbcTemplate jdbc;

    EtaDisplayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> doctorMode(UUID tenantId, UUID doctorId) {
        return jdbc.query("SELECT eta_display_mode FROM doctor_queue_settings WHERE tenant_id = ? AND doctor_id = ? AND eta_display_mode IS NOT NULL",
                (rs, i) -> rs.getString(1), tenantId, doctorId).stream().findFirst();
    }

    Optional<String> facilityMode(UUID tenantId) {
        return jdbc.query("SELECT eta_display_mode FROM facility_queue_settings WHERE tenant_id = ?", (rs, i) -> rs.getString(1), tenantId).stream().findFirst();
    }

    void upsertFacilityMode(UUID tenantId, String mode, UUID by) {
        jdbc.update("INSERT INTO facility_queue_settings (tenant_id, eta_display_mode, updated_by) VALUES (?,?,?) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET eta_display_mode = EXCLUDED.eta_display_mode, updated_by = EXCLUDED.updated_by, updated_at = now()", tenantId, mode, by);
    }

    Optional<String> sessionModeAt(UUID tenantId, UUID doctorId, int dayOfWeek, LocalTime time) {
        return jdbc.query("SELECT eta_display_mode FROM doctor_working_hours WHERE tenant_id = ? AND doctor_id = ? AND day_of_week = ? " +
                        "AND start_time <= ? AND end_time > ? AND eta_display_mode IS NOT NULL LIMIT 1",
                (rs, i) -> rs.getString(1), tenantId, doctorId, dayOfWeek, time, time).stream().findFirst();
    }

    List<SessionMode> sessions(UUID tenantId, UUID doctorId) {
        return jdbc.query("SELECT id, day_of_week, start_time, end_time, eta_display_mode FROM doctor_working_hours " +
                        "WHERE tenant_id = ? AND doctor_id = ? ORDER BY day_of_week, start_time",
                (rs, i) -> new SessionMode(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, LocalTime.class),
                        rs.getObject(4, LocalTime.class), rs.getString(5)), tenantId, doctorId);
    }

    void upsertDoctorMode(UUID tenantId, UUID doctorId, String mode, UUID by) {
        jdbc.update("INSERT INTO doctor_queue_settings (tenant_id, doctor_id, eta_display_mode, updated_by) VALUES (?,?,?,?) " +
                        "ON CONFLICT (doctor_id) DO UPDATE SET eta_display_mode = EXCLUDED.eta_display_mode, updated_by = EXCLUDED.updated_by, updated_at = now()",
                tenantId, doctorId, mode, by);
    }

    int setSessionMode(UUID tenantId, UUID doctorId, UUID workingHoursId, String mode) {
        return jdbc.update("UPDATE doctor_working_hours SET eta_display_mode = ? WHERE tenant_id = ? AND doctor_id = ? AND id = ?",
                mode, tenantId, doctorId, workingHoursId);
    }
}
