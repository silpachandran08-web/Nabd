package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.BreakGlassAlertResponse;
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

    UUID insert(UUID tenantId, UUID staffId, String reasonCode, String reasonText, UUID patientId, Instant expiresAt, UUID sessionId) {
        return jdbc.queryForObject(
                "INSERT INTO break_glass_grants (tenant_id, staff_id, reason_code, reason, patient_id, expires_at, session_id, step_up_verified) " +
                        "VALUES (?,?,?,?,?,?,?,true) RETURNING id",
                UUID.class, tenantId, staffId, reasonCode, reasonText, patientId, Timestamp.from(expiresAt), sessionId);
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
        return jdbc.update("INSERT INTO break_glass_alerts (tenant_id, grant_id, owner_staff_id, message) VALUES (?,?,?,?) " +
                "ON CONFLICT (grant_id, owner_staff_id) DO NOTHING", tenantId, grantId, ownerId, message) == 1;
    }

    void markNotified(UUID grantId) {
        jdbc.update("UPDATE break_glass_grants SET notified_at = now() WHERE id = ? AND notified_at IS NULL", grantId);
    }

    List<BreakGlassAlertResponse.Item> alertsFor(UUID tenantId, UUID ownerId) {
        return jdbc.query("SELECT grant_id, message, created_at FROM break_glass_alerts WHERE tenant_id = ? AND owner_staff_id = ? " +
                        "ORDER BY created_at DESC LIMIT 50",
                (rs, i) -> new BreakGlassAlertResponse.Item(rs.getObject("grant_id", UUID.class), rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, ownerId);
    }
}
