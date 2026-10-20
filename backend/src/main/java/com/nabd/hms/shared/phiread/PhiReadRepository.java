package com.nabd.hms.shared.phiread;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
class PhiReadRepository {

    record Alert(UUID id, UUID actorId, int distinctPatients, int windowMinutes, java.time.Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    PhiReadRepository(JdbcTemplate jdbc, java.time.Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
    }

    /** One multi-row-batched INSERT for all patients of one request. */
    void insert(PhiReadRecorder.Read r, List<UUID> patients) {
        Timestamp at = new Timestamp(r.readAtMillis());
        jdbc.batchUpdate("""
                INSERT INTO phi_read_log (tenant_id, read_at, actor_type, actor_id, session_id, patient_id, resource_type, resource_id,
                                          endpoint, purpose_code, break_glass_grant_id, ip, request_id)
                VALUES (?,?,'staff',?,?,?,?,?,?,?,?,?::inet,?)""", patients, 100, (ps, p) -> {
            ps.setObject(1, r.tenantId());
            ps.setTimestamp(2, at);
            ps.setObject(3, r.actorId());
            ps.setObject(4, r.sessionId());
            ps.setObject(5, p);
            ps.setString(6, r.resourceType());
            ps.setObject(7, r.resourceId());
            ps.setString(8, r.endpoint());
            ps.setString(9, r.purpose());
            ps.setObject(10, r.breakGlassGrantId());
            ps.setString(11, r.ip());
            ps.setString(12, r.requestId());
        });
    }

    /** The visit's patient, for endpoints keyed by queue entry whose body does not name the patient. */
    List<UUID> queueEntryPatient(UUID tenantId, UUID queueEntryId) {
        return jdbc.queryForList("SELECT patient_id FROM queue_entries WHERE tenant_id = ? AND id = ?", UUID.class, tenantId, queueEntryId);
    }

    int distinctPatientsLastHour(UUID tenantId, UUID actorId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(DISTINCT patient_id) FROM phi_read_log
                WHERE tenant_id = ? AND actor_id = ? AND read_at > now() - interval '1 hour' AND resource_type NOT LIKE '%\\_list'""",
                Integer.class, tenantId, actorId);
        return n == null ? 0 : n;
    }

    /** true when this is the actor's first alert in the current clock hour. */
    boolean insertAlert(UUID tenantId, UUID actorId, int distinctPatients, int windowMinutes) {
        return jdbc.update("""
                INSERT INTO phi_read_volume_alerts (tenant_id, actor_id, distinct_patients, window_minutes, hour_bucket)
                VALUES (?,?,?,?, date_trunc('hour', now())) ON CONFLICT (tenant_id, actor_id, hour_bucket) DO NOTHING""",
                tenantId, actorId, distinctPatients, windowMinutes) == 1;
    }

    List<Alert> alerts(UUID tenantId) {
        return jdbc.query("SELECT id, actor_id, distinct_patients, window_minutes, created_at FROM phi_read_volume_alerts " +
                        "WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50",
                (rs, i) -> new Alert(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class), rs.getInt("distinct_patients"),
                        rs.getInt("window_minutes"), rs.getTimestamp("created_at").toInstant()), tenantId);
    }

    int seal() {
        Integer n = jdbc.queryForObject("SELECT seal_phi_read_batches()", Integer.class);
        return n == null ? 0 : n;
    }

    void ensurePartitions() {
        // partitions are UTC days; the injected Clock is UTC (ClockConfig), so this is today's UTC date
        jdbc.queryForObject("SELECT phi_read_log_ensure_partitions(?, 2)", Integer.class, java.sql.Date.valueOf(java.time.LocalDate.now(clock)));
    }
}
