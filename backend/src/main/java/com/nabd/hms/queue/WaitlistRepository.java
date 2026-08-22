package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.WaitlistEntryRow;

@Repository
class WaitlistRepository {

    private static final String SELECT =
            "SELECT w.id, w.doctor_id, w.patient_id, p.name AS patient_name, w.joined_at, w.status, " +
                    "w.offered_slot_start, w.offer_expires_at, w.booked_appointment_id " +
                    "FROM waitlist_entries w JOIN patients p ON p.id = w.patient_id ";

    private final JdbcTemplate jdbc;

    WaitlistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(UUID tenantId, UUID doctorId, UUID patientId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO waitlist_entries (id, tenant_id, doctor_id, patient_id) VALUES (?,?,?,?)",
                id, tenantId, doctorId, patientId);
        return id;
    }

    Optional<WaitlistEntryRow> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT + "WHERE w.tenant_id = ? AND w.id = ?", mapper(), tenantId, id).stream().findFirst();
    }

    /** waiting/offered (active) plus expired (still worth showing — "your offer lapsed") — booked/cancelled are done and drop off. */
    List<WaitlistEntryRow> listForDoctor(UUID tenantId, UUID doctorId) {
        return jdbc.query(SELECT + "WHERE w.tenant_id = ? AND w.doctor_id = ? " +
                        "AND w.status IN ('waiting','offered','expired') ORDER BY w.joined_at",
                mapper(), tenantId, doctorId);
    }

    /** Every 'offered' row whose window has lapsed — the lazy-expiry scan, doctor-wide. */
    List<WaitlistEntryRow> findStaleOffers(UUID tenantId, UUID doctorId) {
        return jdbc.query(SELECT + "WHERE w.tenant_id = ? AND w.doctor_id = ? " +
                        "AND w.status = 'offered' AND w.offer_expires_at <= now() ORDER BY w.joined_at",
                mapper(), tenantId, doctorId);
    }

    Optional<WaitlistEntryRow> findOldestWaiting(UUID tenantId, UUID doctorId) {
        return jdbc.query(SELECT + "WHERE w.tenant_id = ? AND w.doctor_id = ? AND w.status = 'waiting' " +
                        "ORDER BY w.joined_at LIMIT 1",
                mapper(), tenantId, doctorId).stream().findFirst();
    }

    void markExpired(UUID tenantId, UUID id) {
        jdbc.update("UPDATE waitlist_entries SET status = 'expired' WHERE tenant_id = ? AND id = ? AND status = 'offered'",
                tenantId, id);
    }

    void offer(UUID tenantId, UUID id, Instant slotStart, Instant expiresAt) {
        jdbc.update("UPDATE waitlist_entries SET status = 'offered', offered_slot_start = ?, offer_expires_at = ? " +
                "WHERE tenant_id = ? AND id = ? AND status = 'waiting'", Timestamp.from(slotStart), Timestamp.from(expiresAt), tenantId, id);
    }

    /**
     * Atomically claims the offer: only succeeds while still 'offered' and not yet past its
     * expiry — the same check-in-the-WHERE-clause shape as consumeWhatsAppOtp, so a client racing
     * the 15-minute window can never accept a slot that's simultaneously being re-offered to the
     * next person in line.
     */
    boolean claimOffer(UUID tenantId, UUID id, UUID appointmentId) {
        int rows = jdbc.update(
                "UPDATE waitlist_entries SET status = 'booked', booked_appointment_id = ? " +
                        "WHERE tenant_id = ? AND id = ? AND status = 'offered' AND offer_expires_at > now()",
                appointmentId, tenantId, id);
        return rows == 1;
    }

    void cancelMembership(UUID tenantId, UUID id) {
        jdbc.update("UPDATE waitlist_entries SET status = 'cancelled' " +
                "WHERE tenant_id = ? AND id = ? AND status IN ('waiting','offered')", tenantId, id);
    }

    private RowMapper<WaitlistEntryRow> mapper() {
        return (rs, i) -> {
            Timestamp slotStart = rs.getTimestamp("offered_slot_start");
            Timestamp expiresAt = rs.getTimestamp("offer_expires_at");
            String bookedAppointmentId = rs.getString("booked_appointment_id");
            return new WaitlistEntryRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("doctor_id")),
                    UUID.fromString(rs.getString("patient_id")),
                    rs.getString("patient_name"),
                    rs.getTimestamp("joined_at").toInstant(),
                    rs.getString("status"),
                    slotStart == null ? null : slotStart.toInstant(),
                    expiresAt == null ? null : expiresAt.toInstant(),
                    bookedAppointmentId == null ? null : UUID.fromString(bookedAppointmentId));
        };
    }
}
