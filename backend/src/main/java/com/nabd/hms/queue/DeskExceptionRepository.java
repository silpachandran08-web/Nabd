package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-032: desk_exceptions (RLS: every statement runs in a tenant-bound transaction). */
@Repository
class DeskExceptionRepository {

    record Row(UUID id, String kind, String severity, String refType, UUID refId, String params, Instant createdAt, Instant resolvedAt,
               Instant snoozedUntil) {
    }

    private static final String COLS = "id, kind, severity, ref_type, ref_id, summary_params::text, created_at, resolved_at, snoozed_until";

    private final JdbcTemplate jdbc;

    DeskExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp res = rs.getTimestamp(8), sn = rs.getTimestamp(9);
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class),
                rs.getString(6), rs.getTimestamp(7).toInstant(), res == null ? null : res.toInstant(), sn == null ? null : sn.toInstant());
    }

    /** One open row per (kind, ref): a second open for the same thing is a no-op. Returns the new id, empty if it was already open. */
    Optional<UUID> open(UUID tenantId, String kind, String severity, String refType, UUID refId, String paramsJson) {
        return jdbc.query("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id, summary_params) VALUES (?,?,?,?,?,?::jsonb) "
                        + "ON CONFLICT (tenant_id, kind, ref_id) WHERE resolved_at IS NULL DO NOTHING RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, kind, severity, refType, refId, paramsJson).stream().findFirst();
    }

    /** Closes the open rows of these kinds for one ref (the underlying state changed). */
    List<Row> resolveByRef(UUID tenantId, List<String> kinds, UUID refId) {
        return jdbc.query("UPDATE desk_exceptions SET resolved_at = now(), resolution = 'auto' WHERE tenant_id = ? AND ref_id = ? "
                        + "AND kind = ANY (?::text[]) AND resolved_at IS NULL RETURNING " + COLS,
                DeskExceptionRepository::row, tenantId, refId, kinds.toArray(new String[0]));
    }

    Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM desk_exceptions WHERE tenant_id = ? AND id = ?", DeskExceptionRepository::row, tenantId, id)
                .stream().findFirst();
    }

    /** Compare-and-set on "still open": 0 rows means somebody else resolved it first. */
    boolean resolve(UUID tenantId, UUID id, UUID staffId) {
        return jdbc.update("UPDATE desk_exceptions SET resolved_at = now(), resolved_by = ?, resolution = 'manual' "
                + "WHERE tenant_id = ? AND id = ? AND resolved_at IS NULL", staffId, tenantId, id) == 1;
    }

    boolean snooze(UUID tenantId, UUID id, Instant until) {
        return jdbc.update("UPDATE desk_exceptions SET snoozed_until = ? WHERE tenant_id = ? AND id = ? AND resolved_at IS NULL",
                Timestamp.from(until), tenantId, id) == 1;
    }

    List<Row> listOpen(UUID tenantId, String kind, Instant afterCreated, UUID afterId, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM desk_exceptions WHERE tenant_id = ? AND resolved_at IS NULL "
                        + "AND (snoozed_until IS NULL OR snoozed_until <= now()) AND (?::text IS NULL OR kind = ?) "
                        + "AND (?::timestamptz IS NULL OR (created_at, id) > (?::timestamptz, ?::uuid)) ORDER BY created_at, id LIMIT ?",
                DeskExceptionRepository::row, tenantId, kind, kind,
                afterCreated == null ? null : Timestamp.from(afterCreated), afterCreated == null ? null : Timestamp.from(afterCreated), afterId, limit);
    }

    // ---- the sweep: states no event announces (time passing) and the auto-resolve backstop ----

    /** Scheduled bookings starting within the next 2 h that the patient has not confirmed (booked more than 2 h ahead, so the T-2 h reminder was due). */
    int openUnconfirmed(UUID tenantId) {
        return jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id, summary_params) "
                + "SELECT a.tenant_id, 'unconfirmed_booking', 'warn', 'appointment', a.id, jsonb_build_object('doctorId', a.doctor_id, 'startTime', a.start_time) "
                + "FROM appointments a WHERE a.tenant_id = ? AND a.status = 'scheduled' AND a.confirmed_at IS NULL "
                + "AND a.start_time > now() AND a.start_time <= now() + interval '2 hours' AND a.created_at <= a.start_time - interval '2 hours' "
                + "AND NOT EXISTS (SELECT 1 FROM desk_exceptions d WHERE d.tenant_id = a.tenant_id AND d.kind = 'unconfirmed_booking' AND d.ref_id = a.id) "
                + "ON CONFLICT DO NOTHING", tenantId);
    }

    /** Booking requests within 2 minutes of (or past) their review SLA that nobody has decided. */
    int openRequestsNearSla(UUID tenantId) {
        return jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id, summary_params) "
                + "SELECT a.tenant_id, 'request_sla', 'urgent', 'appointment', a.id, jsonb_build_object('doctorId', a.doctor_id, 'slaDueAt', d.sla_due_at) "
                + "FROM booking_request_details d JOIN appointments a ON a.id = d.appointment_id AND a.tenant_id = d.tenant_id "
                + "WHERE d.tenant_id = ? AND a.status = 'requested' AND d.sla_due_at <= now() + interval '2 minutes' "
                + "AND NOT EXISTS (SELECT 1 FROM desk_exceptions x WHERE x.tenant_id = a.tenant_id AND x.kind = 'request_sla' AND x.ref_id = a.id) "
                + "ON CONFLICT DO NOTHING", tenantId);
    }

    /** Visits that have sat in checkout_pending for the configured minutes. */
    int openCheckoutPending(UUID tenantId, int minutes) {
        return jdbc.update("INSERT INTO desk_exceptions (tenant_id, kind, severity, ref_type, ref_id, summary_params) "
                + "SELECT q.tenant_id, 'checkout_unpaid', 'warn', 'queue_entry', q.id, jsonb_build_object('doctorId', q.doctor_id, 'tokenNumber', q.token_number) "
                + "FROM queue_entries q WHERE q.tenant_id = ? AND q.status = 'checkout_pending' AND NOT q.legacy_duplicate "
                + "AND (SELECT max(e.occurred_at) FROM queue_entry_events e WHERE e.tenant_id = q.tenant_id AND e.queue_entry_id = q.id AND e.to_status = 'checkout_pending') "
                + "<= now() - make_interval(mins => ?) "
                + "AND NOT EXISTS (SELECT 1 FROM desk_exceptions x WHERE x.tenant_id = q.tenant_id AND x.kind = 'checkout_unpaid' AND x.ref_id = q.id) "
                + "ON CONFLICT DO NOTHING", tenantId, minutes);
    }

    /** Backstop for the event consumer: close open rows whose underlying state has moved on. Returns the closed rows. */
    List<Row> resolveSettled(UUID tenantId) {
        return jdbc.query("UPDATE desk_exceptions d SET resolved_at = now(), resolution = 'auto' WHERE d.tenant_id = ? AND d.resolved_at IS NULL AND ("
                        + "(d.kind IN ('unconfirmed_booking','checkin_failed') AND d.ref_type = 'appointment' AND EXISTS (SELECT 1 FROM appointments a WHERE a.id = d.ref_id "
                        + "AND (a.status NOT IN ('scheduled') OR (d.kind = 'unconfirmed_booking' AND a.confirmed_at IS NOT NULL)))) "
                        + "OR (d.kind = 'request_sla' AND EXISTS (SELECT 1 FROM appointments a WHERE a.id = d.ref_id AND a.status <> 'requested')) "
                        + "OR (d.kind = 'checkout_unpaid' AND EXISTS (SELECT 1 FROM queue_entries q WHERE q.id = d.ref_id AND q.status <> 'checkout_pending'))"
                        + ") RETURNING d." + COLS.replace(", ", ", d."),
                DeskExceptionRepository::row, tenantId);
    }

    void markConfirmed(UUID tenantId, UUID appointmentId) {
        jdbc.update("UPDATE appointments SET confirmed_at = now() WHERE tenant_id = ? AND id = ? AND confirmed_at IS NULL", tenantId, appointmentId);
    }
}
