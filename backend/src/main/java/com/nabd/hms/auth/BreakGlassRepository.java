package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.BreakGlassAlertResponse;
import com.nabd.hms.auth.dto.BreakGlassReadsResponse;
import com.nabd.hms.auth.dto.BreakGlassReviewQueueResponse;
import com.nabd.hms.auth.dto.BreakGlassResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class BreakGlassRepository {

    record Grant(UUID id, UUID staffId, UUID sessionId, boolean active) {
    }

    record Owner(UUID id, String name, String phone) {
    }

    private final JdbcTemplate jdbc;
    private final com.nabd.hms.common.SessionRevocation.RevokedSessions revokedSessions;

    BreakGlassRepository(JdbcTemplate jdbc, com.nabd.hms.common.SessionRevocation.RevokedSessions revokedSessions) {
        this.jdbc = jdbc;
        this.revokedSessions = revokedSessions;
    }

    boolean hasActive(UUID staffId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM break_glass_grants WHERE staff_id = ? AND deactivated_at IS NULL AND expires_at > now()",
                Integer.class, staffId);
        return n != null && n > 0;
    }

    UUID insert(UUID tenantId, UUID staffId, String reasonCode, String reasonText, UUID patientId, Instant expiresAt, UUID sessionId,
                String deviceLabel) {
        return jdbc.queryForObject(
                "INSERT INTO break_glass_grants (tenant_id, staff_id, reason_code, reason, patient_id, expires_at, session_id, step_up_verified, device_label) " +
                        "VALUES (?,?,?,?,?,?,?,true,?) RETURNING id",
                UUID.class, tenantId, staffId, reasonCode, reasonText, patientId, Timestamp.from(expiresAt), sessionId, deviceLabel);
    }

    Optional<Grant> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, staff_id, session_id, deactivated_at IS NULL AS active FROM break_glass_grants WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Grant(rs.getObject("id", UUID.class), rs.getObject("staff_id", UUID.class),
                        rs.getObject("session_id", UUID.class), rs.getBoolean("active")),
                tenantId, id).stream().findFirst();
    }

    void deactivate(UUID tenantId, UUID id, UUID by, String reason) {
        jdbc.update("UPDATE break_glass_grants SET deactivated_at = now(), deactivated_reason = ?, deactivated_by = ? " +
                "WHERE tenant_id = ? AND id = ? AND deactivated_at IS NULL", reason, by, tenantId, id);
    }

    void revokeSession(UUID tenantId, UUID sessionId) {
        revokedSessions.revoked(jdbc.queryForList( // OPS-020: Valkey revocation cache
                "UPDATE sessions SET revoked_at = now(), revoked_reason = 'admin_revoke' WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL RETURNING id",
                UUID.class, tenantId, sessionId));
    }

    List<BreakGlassResponse> listActive(UUID tenantId) {
        return jdbc.query("SELECT g.id, s.name, g.reason_code, g.activated_at, g.expires_at FROM break_glass_grants g " +
                        "JOIN staff s ON s.id = g.staff_id WHERE g.tenant_id = ? AND g.deactivated_at IS NULL AND g.expires_at > now() " +
                        "ORDER BY g.activated_at DESC",
                (rs, i) -> new BreakGlassResponse(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("reason_code"),
                        rs.getTimestamp("activated_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
                tenantId);
    }

    /** Owners = active staff on the tenant's built-in Owner role. */
    List<Owner> owners(UUID tenantId) {
        return jdbc.query("SELECT s.id, s.name, s.mobile_phone FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND r.built_in = true AND s.status = 'active'",
                (rs, i) -> new Owner(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("mobile_phone")),
                tenantId);
    }

    String staffName(UUID staffId) {
        return jdbc.queryForObject("SELECT name FROM staff WHERE id = ?", String.class, staffId);
    }

    /** true when the alert row is new (the handler is at-least-once). */
    boolean insertAlert(UUID tenantId, UUID grantId, UUID ownerId, String message) {
        return insertAlert(tenantId, grantId, ownerId, message, "activated");
    }

    boolean insertAlert(UUID tenantId, UUID grantId, UUID ownerId, String message, String kind) {
        return jdbc.update("INSERT INTO break_glass_alerts (tenant_id, grant_id, owner_staff_id, message, kind) VALUES (?,?,?,?,?) " +
                "ON CONFLICT (grant_id, owner_staff_id, kind) DO NOTHING", tenantId, grantId, ownerId, message, kind) == 1;
    }

    void markNotified(UUID grantId) {
        jdbc.update("UPDATE break_glass_grants SET notified_at = now() WHERE id = ? AND notified_at IS NULL", grantId);
    }

    List<BreakGlassAlertResponse.Item> alertsFor(UUID tenantId, UUID ownerId) {
        return jdbc.query("SELECT grant_id, message, created_at, kind FROM break_glass_alerts WHERE tenant_id = ? AND owner_staff_id = ? " +
                        "ORDER BY created_at DESC LIMIT 50",
                (rs, i) -> new BreakGlassAlertResponse.Item(rs.getObject("grant_id", UUID.class), rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("kind")),
                tenantId, ownerId);
    }

    // ── PLT-013: owner review ────────────────────────────────────────────

    /** A grant has ended when it was deactivated or ran out (expiry is stamped lazily, so the time decides). */
    private static final String ENDED = "(g.deactivated_at IS NOT NULL OR g.expires_at <= now())";
    private static final String ENDED_AT = "LEAST(COALESCE(g.deactivated_at, g.expires_at), g.expires_at)";

    record ReviewTarget(UUID id, UUID staffId, boolean ended, boolean reviewed) {
    }

    record Reminder(UUID staffId, Instant activatedAt) {
    }

    record Due(UUID tenantId, UUID grantId) {
    }

    /** Ended grants, newest first, keyset on (activated_at, id). Reads come from phi_read_log through its actor index. */
    List<BreakGlassReviewQueueResponse.Item> reviewQueue(UUID tenantId, boolean reviewed, Instant afterActivatedAt, UUID afterId, int limit) {
        return jdbc.query("SELECT g.id, g.staff_id, s.name, g.reason_code, g.reason, g.device_label, g.activated_at, " + ENDED_AT + " AS ended_at, " +
                        "g.review_outcome, g.reviewed_at, (SELECT count(*) FROM phi_read_log r WHERE r.tenant_id = g.tenant_id AND r.actor_id = g.staff_id " +
                        "AND r.read_at >= g.activated_at AND r.read_at <= g.expires_at + interval '1 minute' AND r.break_glass_grant_id = g.id) AS reads " +
                        "FROM break_glass_grants g JOIN staff s ON s.id = g.staff_id AND s.tenant_id = g.tenant_id " +
                        "WHERE g.tenant_id = ? AND " + ENDED + " AND (g.reviewed_at IS NOT NULL) = ? " +
                        "AND (?::timestamptz IS NULL OR (g.activated_at, g.id) < (?::timestamptz, ?::uuid)) " +
                        "ORDER BY g.activated_at DESC, g.id DESC LIMIT ?",
                (rs, i) -> new BreakGlassReviewQueueResponse.Item(rs.getObject("id", UUID.class),
                        new BreakGlassReviewQueueResponse.Staff(rs.getObject("staff_id", UUID.class), rs.getString("name")),
                        rs.getString("reason_code"), rs.getString("reason"), rs.getString("device_label"),
                        rs.getTimestamp("activated_at").toInstant(), rs.getTimestamp("ended_at").toInstant(), rs.getInt("reads"),
                        rs.getString("review_outcome"), rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant()),
                tenantId, reviewed, ts(afterActivatedAt), ts(afterActivatedAt), afterId, limit);
    }

    Optional<ReviewTarget> reviewTarget(UUID tenantId, UUID id) {
        return jdbc.query("SELECT g.id, g.staff_id, " + ENDED + " AS ended, g.reviewed_at IS NOT NULL AS reviewed FROM break_glass_grants g " +
                        "WHERE g.tenant_id = ? AND g.id = ?",
                (rs, i) -> new ReviewTarget(rs.getObject("id", UUID.class), rs.getObject("staff_id", UUID.class), rs.getBoolean("ended"),
                        rs.getBoolean("reviewed")), tenantId, id).stream().findFirst();
    }

    /** What the holder opened during the grant, oldest first (a timeline). Identifiers, name and MRN only: no clinical content. */
    List<BreakGlassReadsResponse.Item> reads(UUID tenantId, UUID grantId) {
        return jdbc.query("SELECT r.patient_id, p.mrn, p.name, r.resource_type, r.read_at FROM break_glass_grants g " +
                        "JOIN phi_read_log r ON r.tenant_id = g.tenant_id AND r.actor_id = g.staff_id AND r.read_at >= g.activated_at " +
                        "AND r.read_at <= g.expires_at + interval '1 minute' AND r.break_glass_grant_id = g.id " +
                        "LEFT JOIN patients p ON p.tenant_id = r.tenant_id AND p.id = r.patient_id " +
                        "WHERE g.tenant_id = ? AND g.id = ? ORDER BY r.read_at, r.id LIMIT 1000",
                (rs, i) -> new BreakGlassReadsResponse.Item(rs.getObject("patient_id", UUID.class), rs.getString("mrn"), rs.getString("name"),
                        rs.getString("resource_type"), rs.getTimestamp("read_at").toInstant()),
                tenantId, grantId);
    }

    /** Records the outcome once; empty when another review got there first. */
    Optional<Instant> markReviewed(UUID tenantId, UUID id, UUID by, String outcome, String note) {
        return jdbc.query("UPDATE break_glass_grants g SET reviewed_by = ?, reviewed_at = now(), review_outcome = ?, review_note = ? " +
                        "WHERE g.tenant_id = ? AND g.id = ? AND g.reviewed_at IS NULL AND " + ENDED + " RETURNING g.reviewed_at",
                (rs, i) -> rs.getTimestamp("reviewed_at").toInstant(), by, outcome, note, tenantId, id).stream().findFirst();
    }

    /** Takes clinical:breakGlass from this staff member only (TokenService drops it; BreakGlassService refuses activation). */
    void removeBreakGlass(UUID tenantId, UUID staffId) {
        jdbc.update("UPDATE staff SET break_glass_revoked_at = now() WHERE tenant_id = ? AND id = ? AND break_glass_revoked_at IS NULL",
                tenantId, staffId);
    }

    /** Across clinics, through the V3280 definer: ids only. */
    List<Due> dueReminders(Instant now) {
        return jdbc.query("SELECT tenant_id, id FROM due_break_glass_reminders(?)",
                (rs, i) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)), Timestamp.from(now));
    }

    /** Stamps the one reminder; empty when it was already sent or the grant got reviewed meanwhile. */
    Optional<Reminder> stampReminder(UUID tenantId, UUID id) {
        return jdbc.query("UPDATE break_glass_grants SET review_reminded_at = now() " +
                        "WHERE tenant_id = ? AND id = ? AND review_reminded_at IS NULL AND reviewed_at IS NULL RETURNING staff_id, activated_at",
                (rs, i) -> new Reminder(rs.getObject("staff_id", UUID.class), rs.getTimestamp("activated_at").toInstant()),
                tenantId, id).stream().findFirst();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
