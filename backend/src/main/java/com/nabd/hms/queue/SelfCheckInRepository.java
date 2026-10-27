package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-033: facility_checkin_settings and checkin_attempts (RLS: tenant-bound transactions only). */
@Repository
class SelfCheckInRepository {

    record Settings(String code, BigDecimal lat, BigDecimal lng, boolean geofenceRequired, int radiusM, boolean walkinAllowed, String waNumber) {
    }

    record TodayAppointment(UUID id, UUID doctorId, Instant startTime, String status) {
    }

    record Attempt(UUID id, UUID appointmentId, String channel, Integer distanceM, String outcome, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    SelfCheckInRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Settings> settings(UUID tenantId) {
        return jdbc.query("SELECT checkin_code, geo_lat, geo_lng, geofence_required, geofence_radius_m, walkin_allowed, wa_number "
                        + "FROM facility_checkin_settings WHERE tenant_id = ?",
                (rs, i) -> new Settings(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBoolean(4), rs.getInt(5), rs.getBoolean(6), rs.getString(7)),
                tenantId).stream().findFirst();
    }

    /** Creates the row with this code, or updates the settings and keeps the existing code. */
    void upsert(UUID tenantId, String newCode, BigDecimal lat, BigDecimal lng, boolean required, int radiusM, boolean walkin, String waNumber) {
        jdbc.update("INSERT INTO facility_checkin_settings (tenant_id, checkin_code, geo_lat, geo_lng, geofence_required, geofence_radius_m, walkin_allowed, wa_number) "
                        + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id) DO UPDATE SET geo_lat = EXCLUDED.geo_lat, geo_lng = EXCLUDED.geo_lng, "
                        + "geofence_required = EXCLUDED.geofence_required, geofence_radius_m = EXCLUDED.geofence_radius_m, "
                        + "walkin_allowed = EXCLUDED.walkin_allowed, wa_number = EXCLUDED.wa_number, updated_at = now()",
                tenantId, newCode, lat, lng, required, radiusM, walkin, waNumber);
    }

    /** The patient's live appointments inside [from, to) (the clinic day), earliest first. */
    List<TodayAppointment> appointmentsBetween(UUID tenantId, UUID patientId, Instant from, Instant to) {
        return jdbc.query("SELECT id, doctor_id, start_time, status FROM appointments WHERE tenant_id = ? AND patient_id = ? "
                        + "AND status IN ('scheduled','arrived') AND start_time >= ? AND start_time < ? ORDER BY start_time",
                (rs, i) -> new TodayAppointment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant(), rs.getString(4)),
                tenantId, patientId, Timestamp.from(from), Timestamp.from(to));
    }

    /** Only the distance is kept, never the coordinates the patient shared. */
    void recordAttempt(UUID tenantId, UUID id, UUID appointmentId, String channel, Integer distanceM, String outcome) {
        jdbc.update("INSERT INTO checkin_attempts (id, tenant_id, appointment_id, channel, distance_m, outcome) VALUES (?,?,?,?,?,?)",
                id, tenantId, appointmentId, channel, distanceM, outcome);
    }

    List<Attempt> recent(UUID tenantId, int limit) {
        return jdbc.query("SELECT id, appointment_id, channel, distance_m, outcome, created_at FROM checkin_attempts WHERE tenant_id = ? "
                        + "ORDER BY created_at DESC, id LIMIT ?",
                (rs, i) -> new Attempt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), (Integer) rs.getObject(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()),
                tenantId, limit);
    }
}
