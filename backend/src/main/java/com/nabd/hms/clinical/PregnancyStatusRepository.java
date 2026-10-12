package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class PregnancyStatusRepository {

    record StatusRow(String status, LocalDate expectedDeliveryDate, UUID recordedBy, Instant recordedAt) {
    }

    record EventRow(UUID id, String beforeJson, String afterJson, UUID actorId, Instant occurredAt) {
    }

    private final JdbcTemplate jdbc;

    PregnancyStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StatusRow> find(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT status, expected_delivery_date, recorded_by, recorded_at FROM patient_pregnancy_status " +
                        "WHERE tenant_id = ? AND patient_id = ?",
                (rs, i) -> new StatusRow(rs.getString("status"),
                        rs.getDate("expected_delivery_date") == null ? null : rs.getDate("expected_delivery_date").toLocalDate(),
                        UUID.fromString(rs.getString("recorded_by")), rs.getTimestamp("recorded_at").toInstant()),
                tenantId, patientId).stream().findFirst();
    }

    void upsert(UUID tenantId, UUID patientId, String status, LocalDate edd, UUID by) {
        jdbc.update("INSERT INTO patient_pregnancy_status (tenant_id, patient_id, status, expected_delivery_date, recorded_by) " +
                        "VALUES (?,?,?,?,?) ON CONFLICT (tenant_id, patient_id) DO UPDATE SET status = EXCLUDED.status, " +
                        "expected_delivery_date = EXCLUDED.expected_delivery_date, recorded_by = EXCLUDED.recorded_by, recorded_at = now()",
                tenantId, patientId, status, edd, by);
    }

    void insertEvent(UUID tenantId, UUID patientId, String beforeJson, String afterJson, UUID actorId) {
        jdbc.update("INSERT INTO patient_pregnancy_events (tenant_id, patient_id, before, after, actor_id) " +
                        "VALUES (?,?,?::jsonb,?::jsonb,?)", tenantId, patientId, beforeJson, afterJson, actorId);
    }

    List<EventRow> findEvents(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT id, before::text AS before_json, after::text AS after_json, actor_id, occurred_at " +
                        "FROM patient_pregnancy_events WHERE tenant_id = ? AND patient_id = ? ORDER BY occurred_at DESC, id",
                (rs, i) -> new EventRow(UUID.fromString(rs.getString("id")), rs.getString("before_json"), rs.getString("after_json"),
                        UUID.fromString(rs.getString("actor_id")), rs.getTimestamp("occurred_at").toInstant()),
                tenantId, patientId);
    }
}
