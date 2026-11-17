package com.nabd.hms.packages;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** COM-044: the package tables' side of the card, nudges, expiry warnings, delivery status and 'Book session N of M'. */
@Repository
class PackageMessagingRepository {

    /** Delivery statuses in the order they may move: a late 'delivered' after 'read' (or after a terminal one) changes nothing. */
    private static final String RANK = "CASE %s WHEN 'requested' THEN 0 WHEN 'delivered' THEN 1 WHEN 'read' THEN 2 ELSE 3 END";

    private final JdbcTemplate jdbc;

    PackageMessagingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Messaging(UUID soldBy, Integer nudgeIntervalDays, LocalDate nextNudgeAt) {
    }

    Optional<Messaging> messaging(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT sold_by, nudge_interval_days, next_session_nudge_at FROM package_instances WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Messaging(rs.getObject(1, UUID.class), (Integer) rs.getObject(2),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate()), tenantId, instanceId).stream().findFirst();
    }

    void setNudge(UUID tenantId, UUID instanceId, Integer intervalDays, LocalDate nextAt) {
        jdbc.update("UPDATE package_instances SET nudge_interval_days = ?, next_session_nudge_at = ? WHERE tenant_id = ? AND id = ?",
                intervalDays, nextAt == null ? null : Date.valueOf(nextAt), tenantId, instanceId);
    }

    void setNextNudge(UUID tenantId, UUID instanceId, LocalDate nextAt) {
        jdbc.update("UPDATE package_instances SET next_session_nudge_at = ? WHERE tenant_id = ? AND id = ?",
                nextAt == null ? null : Date.valueOf(nextAt), tenantId, instanceId);
    }

    void resetAlertTier(UUID tenantId, UUID instanceId) {
        jdbc.update("UPDATE package_instances SET last_alert_tier = NULL WHERE tenant_id = ? AND id = ?", tenantId, instanceId);
    }

    /** Active instances the daily job looks at: within 30 days of the end through the grace end, or with a session nudge due. */
    List<UUID> dueForMessaging(UUID tenantId, LocalDate today) {
        Date d = Date.valueOf(today);
        return jdbc.query("SELECT id FROM package_instances WHERE tenant_id = ? AND status = 'active' AND ("
                        + "(validity_end IS NOT NULL AND ?::date >= validity_end - 30 AND ?::date <= validity_end + grace_days) "
                        + "OR next_session_nudge_at <= ?::date) ORDER BY id",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, d, d, d);
    }

    boolean hasEvent(UUID tenantId, UUID instanceId, String eventType) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM package_instance_events WHERE tenant_id = ? AND instance_id = ? AND event_type = ?)",
                Boolean.class, tenantId, instanceId, eventType));
    }

    void insertMessageEvent(UUID tenantId, UUID instanceId, String eventType, String note, UUID actorId, UUID requestId, String deliveryStatus) {
        jdbc.update("INSERT INTO package_instance_events (tenant_id, instance_id, event_type, note, actor_id, notification_request_id, delivery_status) "
                + "VALUES (?,?,?,?,?,?,?)", tenantId, instanceId, eventType, note, actorId, requestId, deliveryStatus);
    }

    /** Forward-only: the status moves only to a higher rank (requested < delivered < read < failed/cancelled/expired); blocked never moves. */
    int updateDelivery(UUID tenantId, UUID requestId, String status) {
        return jdbc.update("UPDATE package_instance_events SET delivery_status = ? WHERE tenant_id = ? AND notification_request_id = ? "
                        + "AND delivery_status IS DISTINCT FROM 'blocked' AND " + RANK.formatted("delivery_status") + " < " + RANK.formatted("?::text"),
                status, tenantId, requestId, status);
    }

    /** The newest expiry reminder's delivery status (the desk's tick), null when none was asked for. */
    Optional<String> lastReminderStatus(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT delivery_status FROM package_instance_events WHERE tenant_id = ? AND instance_id = ? AND event_type = 'expiry_warning_sent' "
                        + "AND notification_request_id IS NOT NULL ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> rs.getString(1), tenantId, instanceId).stream().findFirst();
    }

    // ── 'Book session N of M' ─────────────────────────────────────────────────

    /** Bookable sessions of an instance: items other than take-home products. */
    record Sessions(UUID instanceId, UUID packageId, int total, int used, int booked) {
    }

    List<Sessions> sessionsOfPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT i.id, i.package_id, "
                        + "COALESCE(SUM(it.quantity_total), 0), COALESCE(SUM(it.quantity_consumed), 0), "
                        + "(SELECT count(*) FROM package_redemptions r JOIN package_instance_items x ON x.id = r.instance_item_id "
                        + " WHERE r.tenant_id = i.tenant_id AND x.instance_id = i.id AND r.status = 'booked') "
                        + "FROM package_instances i JOIN package_instance_items it ON it.instance_id = i.id AND it.item_type <> 'take_home_product' "
                        + "WHERE i.tenant_id = ? AND i.patient_id = ? AND i.status = 'active' "
                        + "AND NOT EXISTS (SELECT 1 FROM package_refunds f WHERE f.tenant_id = i.tenant_id AND f.instance_id = i.id AND f.status = 'pending') "
                        + "GROUP BY i.id, i.package_id, i.tenant_id ORDER BY i.created_at",
                (rs, n) -> new Sessions(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), rs.getInt(5)),
                tenantId, patientId);
    }

    List<UUID> eligibleDoctors(UUID tenantId, UUID packageId) {
        return jdbc.query("SELECT doctor_id FROM package_eligible_doctors WHERE tenant_id = ? AND package_id = ? ORDER BY doctor_id",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, packageId);
    }

    /** The first bookable item with a session neither used nor already booked. */
    Optional<UUID> itemWithRoom(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT it.id FROM package_instance_items it WHERE it.tenant_id = ? AND it.instance_id = ? AND it.item_type <> 'take_home_product' "
                        + "AND it.quantity_total - it.quantity_consumed > (SELECT count(*) FROM package_redemptions r WHERE r.tenant_id = it.tenant_id "
                        + "AND r.instance_item_id = it.id AND r.status = 'booked') ORDER BY it.name, it.id LIMIT 1",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, instanceId).stream().findFirst();
    }

    void insertBookedRedemption(UUID tenantId, UUID instanceItemId, UUID appointmentId) {
        jdbc.update("INSERT INTO package_redemptions (tenant_id, instance_item_id, booked_by, appointment_id) VALUES (?,?,NULL,?)",
                tenantId, instanceItemId, appointmentId);
    }

    /** booked -> cancelled for the appointment's session; the instance it belongs to, empty when there was nothing to release
     * (no package booking, already released, or redeemed: a redeemed session is never given back). */
    Optional<UUID> releaseBooked(UUID tenantId, UUID appointmentId) {
        return jdbc.query("UPDATE package_redemptions r SET status = 'cancelled' FROM package_instance_items x "
                        + "WHERE x.id = r.instance_item_id AND r.tenant_id = ? AND r.appointment_id = ? AND r.status = 'booked' RETURNING x.instance_id",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, appointmentId).stream().findFirst();
    }

    void moveBooked(UUID tenantId, UUID fromAppointmentId, UUID toAppointmentId) {
        jdbc.update("UPDATE package_redemptions SET appointment_id = ? WHERE tenant_id = ? AND appointment_id = ? AND status = 'booked'",
                toAppointmentId, tenantId, fromAppointmentId);
    }

    // ── the card behind the secure link ────────────────────────────────────────

    record Money(BigDecimal total, BigDecimal paid, String currency) {
    }

    /** The sale invoice's total and paid amount (paid/due on the card). */
    Optional<Money> money(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT inv.total, inv.paid, i.currency FROM package_instances i JOIN invoices inv ON inv.id = i.invoice_id WHERE i.tenant_id = ? AND i.id = ?",
                (rs, i) -> new Money(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)), tenantId, instanceId).stream().findFirst();
    }
}
