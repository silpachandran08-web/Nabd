package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-009/010: SQL for booking requests, their details, the routing policy and the offers made to patients. */
@Repository
class BookingRequestRepository {

    /** One held (or decided) booking request. patientNote is never read back: it is for the reviewer's screen only, not these rows. */
    record Req(UUID id, UUID patientId, UUID doctorId, Instant start, Instant end, String status, String channel, String visitType,
               String reviewReason, List<String> triageFlags, Instant slaDueAt, Instant createdAt) {
    }

    record ListRow(Req req, String patientName, java.time.LocalDate dob) {
    }

    record Offer(UUID id, UUID appointmentId, List<Instant> slots, String status, Instant expiresAt, UUID createdBy) {
    }

    private static final String REQ_COLS = "a.id, a.patient_id, a.doctor_id, a.start_time, a.end_time, a.status, a.channel, a.visit_type, "
            + "d.review_reason, d.triage_flags, d.sla_due_at, a.created_at ";

    private final JdbcTemplate jdbc;

    BookingRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    BookingDecision.Policy policy(UUID tenantId) {
        return jdbc.query("SELECT acceptance_mode, daily_auto_cap, review_sla_minutes, policy_version FROM booking_routing_settings WHERE tenant_id = ?",
                (rs, i) -> new BookingDecision.Policy(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), tenantId)
                .stream().findFirst().orElse(BookingDecision.Policy.DEFAULT);
    }

    /** The patient has been seen by this doctor before (a completed visit or an arrived appointment). */
    boolean hasHistory(UUID tenantId, UUID patientId, UUID doctorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM appointments WHERE tenant_id = ? AND patient_id = ? AND doctor_id = ? AND (status IN ('arrived','completed') OR is_follow_up)) "
                        + "OR EXISTS(SELECT 1 FROM queue_entries WHERE tenant_id = ? AND patient_id = ? AND doctor_id = ? AND status = 'completed')",
                Boolean.class, tenantId, patientId, doctorId, tenantId, patientId, doctorId));
    }

    int autoConfirmed(UUID tenantId, UUID doctorId, Instant dayStart, Instant dayEnd) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE tenant_id = ? AND doctor_id = ? AND decision_source = 'auto' "
                + "AND status IN ('scheduled','arrived','completed') AND start_time >= ? AND start_time < ?",
                Integer.class, tenantId, doctorId, Timestamp.from(dayStart), Timestamp.from(dayEnd));
        return n == null ? 0 : n;
    }

    /** Throws DataIntegrityViolationException when the slot is held (ex_appointments_doctor_overlap). */
    UUID insertRouted(UUID tenantId, UUID patientId, UUID doctorId, Instant start, Instant end, String status, String channel,
                      String visitType, String decisionSource, int policyVersion, Instant decidedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, channel, visit_type, "
                        + "decision_source, policy_version, decided_at, is_follow_up) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, patientId, doctorId, Timestamp.from(start), Timestamp.from(end), status, channel, visitType,
                decisionSource, policyVersion, decidedAt == null ? null : Timestamp.from(decidedAt), "follow_up".equals(visitType));
        return id;
    }

    void insertDetails(UUID tenantId, UUID appointmentId, String reason, List<String> flags, String note, Instant slaDueAt) {
        jdbc.update("INSERT INTO booking_request_details (appointment_id, tenant_id, review_reason, triage_flags, patient_note, sla_due_at) VALUES (?,?,?,?::text[],?,?)",
                appointmentId, tenantId, reason, textArray(flags), note, Timestamp.from(slaDueAt));
    }

    Optional<Req> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + REQ_COLS + "FROM appointments a JOIN booking_request_details d ON d.appointment_id = a.id WHERE a.tenant_id = ? AND a.id = ?",
                (rs, i) -> req(rs), tenantId, id).stream().findFirst();
    }

    List<ListRow> list(UUID tenantId, boolean open, UUID doctorId, Instant decidedSince, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + REQ_COLS + ", p.name AS patient_name, p.dob FROM appointments a "
                + "JOIN booking_request_details d ON d.appointment_id = a.id JOIN patients p ON p.id = a.patient_id WHERE a.tenant_id = ? ");
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (open) {
            sql.append("AND a.status = 'requested' ");
        } else {
            sql.append("AND a.status <> 'requested' AND a.decided_at >= ? ");
            args.add(Timestamp.from(decidedSince));
        }
        if (doctorId != null) {
            sql.append("AND a.doctor_id = ? ");
            args.add(doctorId);
        }
        sql.append(open ? "ORDER BY (cardinality(d.triage_flags) > 0) DESC, d.sla_due_at, a.id " : "ORDER BY a.decided_at DESC, a.id ");
        sql.append("LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> new ListRow(req(rs), rs.getString("patient_name"), rs.getDate("dob").toLocalDate()), args.toArray());
    }

    /** Auto-confirmed count and median reply time (seconds) of the requests decided since the given instant. */
    record Stats(int autoAccepted, Double medianReplySeconds) {
    }

    Stats stats(UUID tenantId, Instant since) {
        int auto = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE tenant_id = ? AND decision_source = 'auto' AND decided_at >= ?",
                Integer.class, tenantId, Timestamp.from(since));
        Double median = jdbc.queryForObject("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM a.decided_at - a.created_at)) "
                + "FROM appointments a JOIN booking_request_details d ON d.appointment_id = a.id WHERE a.tenant_id = ? AND a.decided_at >= ? AND a.status <> 'requested'",
                Double.class, tenantId, Timestamp.from(since));
        return new Stats(auto, median);
    }

    /** Another active booking (requested, scheduled, arrived) overlaps [start, end) for the doctor, ignoring the request itself. */
    boolean overlaps(UUID tenantId, UUID doctorId, Instant start, Instant end, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM appointments WHERE tenant_id = ? AND doctor_id = ? AND id <> ? "
                + "AND booking_kind = 'slot' AND status IN ('requested','scheduled','arrived') AND tstzrange(start_time, end_time, '[)') && tstzrange(?, ?, '[)'))",
                Boolean.class, tenantId, doctorId, exceptId, Timestamp.from(start), Timestamp.from(end)));
    }

    /** The compare-and-set every decision goes through: 0 rows means someone else decided first. */
    int decide(UUID tenantId, UUID id, String newStatus, Instant start, Instant end, UUID decidedBy, Instant at) {
        return jdbc.update("UPDATE appointments SET status = ?, start_time = ?, end_time = ?, decision_source = 'staff', decided_by = ?, decided_at = ? "
                + "WHERE tenant_id = ? AND id = ? AND status = 'requested'",
                newStatus, Timestamp.from(start), Timestamp.from(end), decidedBy, Timestamp.from(at), tenantId, id);
    }

    void recordDecline(UUID tenantId, UUID id, String reasonCode) {
        jdbc.update("UPDATE booking_request_details SET decline_reason_code = ? WHERE tenant_id = ? AND appointment_id = ?", reasonCode, tenantId, id);
        closeOffers(tenantId, id);
    }

    void closeOffers(UUID tenantId, UUID appointmentId) {
        jdbc.update("UPDATE booking_request_offers SET status = 'expired' WHERE tenant_id = ? AND appointment_id = ? AND status = 'open'", tenantId, appointmentId);
    }

    UUID insertOffer(UUID tenantId, UUID appointmentId, List<Instant> slots, Instant expiresAt, UUID createdBy) {
        UUID id = UUID.randomUUID();
        List<String> iso = slots.stream().map(Instant::toString).toList();
        jdbc.update("INSERT INTO booking_request_offers (id, tenant_id, appointment_id, slots, expires_at, created_by) VALUES (?,?,?,?::timestamptz[],?,?)",
                id, tenantId, appointmentId, textArray(iso), Timestamp.from(expiresAt), createdBy);
        return id;
    }

    Optional<Offer> findOffer(UUID tenantId, UUID offerId) {
        return jdbc.query("SELECT id, appointment_id, slots, status, expires_at, created_by FROM booking_request_offers WHERE tenant_id = ? AND id = ?",
                (rs, i) -> {
                    List<Instant> slots = new ArrayList<>();
                    try {
                        for (Object o : (Object[]) rs.getArray("slots").getArray()) slots.add(((Timestamp) o).toInstant());
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    return new Offer(rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class), slots, rs.getString("status"),
                            rs.getTimestamp("expires_at").toInstant(), rs.getObject("created_by", UUID.class));
                }, tenantId, offerId).stream().findFirst();
    }

    /** Closes an open offer with its outcome (chosen / none); 0 rows = already closed. */
    int closeOffer(UUID tenantId, UUID offerId, String status, Instant chosenSlot) {
        return jdbc.update("UPDATE booking_request_offers SET status = ?, chosen_slot = ? WHERE tenant_id = ? AND id = ? AND status = 'open'",
                status, chosenSlot == null ? null : Timestamp.from(chosenSlot), tenantId, offerId);
    }

    /** Open requests past their SLA that were not escalated yet. */
    List<Req> dueForEscalation(UUID tenantId, Instant now, int limit) {
        return jdbc.query("SELECT " + REQ_COLS + "FROM booking_request_details d JOIN appointments a ON a.id = d.appointment_id "
                + "WHERE d.tenant_id = ? AND a.status = 'requested' AND d.escalated_at IS NULL AND d.sla_due_at <= ? ORDER BY d.sla_due_at LIMIT ?",
                (rs, i) -> req(rs), tenantId, Timestamp.from(now), limit);
    }

    /** CAS: true for the one caller that marks it, so the escalation is raised once. */
    boolean markEscalated(UUID tenantId, UUID appointmentId, Instant at) {
        return jdbc.update("UPDATE booking_request_details SET escalated_at = ? WHERE tenant_id = ? AND appointment_id = ? AND escalated_at IS NULL",
                Timestamp.from(at), tenantId, appointmentId) == 1;
    }

    private static Req req(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> flags = new ArrayList<>();
        for (Object o : (Object[]) rs.getArray("triage_flags").getArray()) flags.add((String) o);
        return new Req(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getObject("doctor_id", UUID.class),
                rs.getTimestamp("start_time").toInstant(), rs.getTimestamp("end_time").toInstant(), rs.getString("status"), rs.getString("channel"),
                rs.getString("visit_type"), rs.getString("review_reason"), flags, rs.getTimestamp("sla_due_at").toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    /** Flags are validated against [a-z0-9_]+ and instants are ISO text, so a plain quoted literal is safe. */
    private static String textArray(List<String> values) {
        return "{" + String.join(",", values.stream().map(v -> "\"" + v + "\"").toList()) + "}";
    }
}
