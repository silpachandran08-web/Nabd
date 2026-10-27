package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-006: appointment_change_offers. RLS scopes every query to the bound tenant; tenant_id is still passed so the index is used. */
@Repository
class ChangeOfferRepository {

    record Offer(UUID id, UUID appointmentId, String reasonCode, List<Instant> slots, String status, Instant expiresAt, UUID newAppointmentId) {
    }

    private final JdbcTemplate jdbc;

    ChangeOfferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A newer offer replaces the open one(s) for the same appointment. */
    void supersedeOpen(UUID tenantId, UUID appointmentId) {
        jdbc.update("UPDATE appointment_change_offers SET status = 'superseded' WHERE tenant_id = ? AND appointment_id = ? AND status = 'sent'", tenantId, appointmentId);
    }

    UUID insert(UUID tenantId, UUID appointmentId, String reasonCode, List<Instant> slots, Instant expiresAt, UUID createdBy) {
        UUID id = UUID.randomUUID();
        String arr = "{" + String.join(",", slots.stream().map(s -> "\"" + s + "\"").toList()) + "}";
        jdbc.update("INSERT INTO appointment_change_offers (id, tenant_id, appointment_id, reason_code, slots, expires_at, created_by) VALUES (?,?,?,?,?::timestamptz[],?,?)",
                id, tenantId, appointmentId, reasonCode, arr, Timestamp.from(expiresAt), createdBy);
        return id;
    }

    /** Locks the row: two taps on the same offer are serialised. */
    Optional<Offer> lock(UUID tenantId, UUID offerId) {
        return jdbc.query("SELECT id, appointment_id, reason_code, slots, status, expires_at, new_appointment_id FROM appointment_change_offers WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, i) -> map(rs), tenantId, offerId).stream().findFirst();
    }

    /** The newest offer per appointment, for the desk's status chip. */
    Optional<Offer> latestFor(UUID tenantId, UUID appointmentId) {
        return jdbc.query("SELECT id, appointment_id, reason_code, slots, status, expires_at, new_appointment_id FROM appointment_change_offers WHERE tenant_id = ? AND appointment_id = ? ORDER BY sent_at DESC LIMIT 1",
                (rs, i) -> map(rs), tenantId, appointmentId).stream().findFirst();
    }

    void close(UUID tenantId, UUID offerId, String status, Instant acceptedSlot, UUID newAppointmentId) {
        jdbc.update("UPDATE appointment_change_offers SET status = ?, accepted_slot = ?, new_appointment_id = ? WHERE tenant_id = ? AND id = ?",
                status, acceptedSlot == null ? null : Timestamp.from(acceptedSlot), newAppointmentId, tenantId, offerId);
    }

    /** Why the booking needs moving: the flag a leave block set, else a plain clinic-initiated change. */
    String reasonFor(UUID tenantId, UUID appointmentId) {
        return jdbc.queryForObject("SELECT coalesce(needs_reschedule_reason, 'clinic_initiated') FROM appointments WHERE tenant_id = ? AND id = ?", String.class, tenantId, appointmentId);
    }

    private static Offer map(java.sql.ResultSet rs) throws SQLException {
        List<Instant> slots = new ArrayList<>();
        for (Object o : (Object[]) rs.getArray("slots").getArray()) slots.add(((Timestamp) o).toInstant());
        return new Offer(rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class), rs.getString("reason_code"), slots,
                rs.getString("status"), rs.getTimestamp("expires_at").toInstant(), rs.getObject("new_appointment_id", UUID.class));
    }
}
