package com.nabd.hms.auth.internal.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.auth.internal.store.AuthModels.Role;
import static com.nabd.hms.auth.internal.store.AuthModels.SessionRow;
import static com.nabd.hms.auth.internal.store.AuthModels.Staff;
import static com.nabd.hms.auth.internal.store.AuthModels.Tenant;

/** PLT-034 (split from AuthRepository): refresh-token sessions and their families. OPS-020: every revoke RETURNs the ids
 * and passes them to the Valkey revocation cache (see revoked()). */
@Repository
public class AuthSessionRepository {

    private final JdbcTemplate jdbc;
    private final com.nabd.hms.common.SessionRevocation.RevokedSessions revokedSessions;

    AuthSessionRepository(JdbcTemplate jdbc, com.nabd.hms.common.SessionRevocation.RevokedSessions revokedSessions) {
        this.jdbc = jdbc;
        this.revokedSessions = revokedSessions;
    }

    /** OPS-020: every revoke statement RETURNs the revoked ids and passes them here (Valkey revocation cache). */
    private List<UUID> revoked(List<UUID> sessionIds) {
        revokedSessions.revoked(sessionIds);
        return sessionIds;
    }


    public void insertSession(UUID id, UUID tenantId, UUID staffId, UUID familyId, String tokenHash,
                        Instant expiresAt, String deviceLabel, String ip, String userAgent, Instant now) {
        jdbc.update(
                "INSERT INTO sessions (id, tenant_id, staff_id, family_id, token_hash, expires_at, device_label, ip_address, user_agent, family_started_at) " +
                        "VALUES (?,?,?,?,?,?,?,?::inet,?, COALESCE((SELECT min(family_started_at) FROM sessions WHERE tenant_id = ? AND family_id = ?), ?))",
                id, tenantId, staffId, familyId, tokenHash, Timestamp.from(expiresAt), deviceLabel, ip, userAgent,
                tenantId, familyId, Timestamp.from(now) // PLT-007: a rotated session keeps its family's start (12 h cap)
        );
    }

    /** PLT-007: when the refresh family of this session was first signed in. */
    public Instant familyStartedAt(UUID tenantId, UUID sessionId) {
        return jdbc.queryForObject("SELECT family_started_at FROM sessions WHERE tenant_id = ? AND id = ?",
                Timestamp.class, tenantId, sessionId).toInstant();
    }

    /** PLT-007: tenant suspended/offboarded, one UPDATE for every live session (partial index on tenant_id); one staff id per revoked session. */
    public List<UUID> revokeAllForTenant(UUID tenantId, String reason) {
        List<UUID[]> rows = jdbc.query("UPDATE sessions SET revoked_at = now(), revoked_reason = ? " +
                "WHERE tenant_id = ? AND revoked_at IS NULL RETURNING id, staff_id",
                (rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, reason, tenantId);
        revoked(rows.stream().map(r -> r[0]).toList());
        return rows.stream().map(r -> r[1]).toList();
    }

    /**
     * Bootstrap lookup — runs before the tenant (and therefore RLS context) is known, via the
     * SECURITY DEFINER function defined in V1__staff_auth_core.sql. Every other session query in
     * this class goes through plain RLS-scoped SQL once TenantContext.set has been called.
     */
    public Optional<SessionRow> findSessionByTokenHash(String tokenHash) {
        return jdbc.query(
                "SELECT id, tenant_id, staff_id, family_id, token_hash, expires_at, revoked_at, revoked_reason, device_label, ip_address::text, last_seen_at " +
                        "FROM find_session_by_token_hash(?)",
                sessionMapper(), tokenHash
        ).stream().findFirst();
    }

    public List<SessionRow> listActiveSessions(UUID staffId) {
        return jdbc.query(
                "SELECT id, tenant_id, staff_id, family_id, token_hash, expires_at, revoked_at, revoked_reason, device_label, ip_address::text, last_seen_at " +
                        "FROM sessions WHERE staff_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC",
                sessionMapper(), staffId
        );
    }

    /** NB-043: is this specific session still live? — the check that makes a revoke take effect on the very next request, not just at the access token's natural (15 min) expiry. */
    public boolean isSessionActive(UUID tenantId, UUID sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL AND expires_at > now()",
                Integer.class, tenantId, sessionId);
        return count != null && count > 0;
    }

    public void revokeSession(UUID sessionId, String reason) {
        revoked(jdbc.queryForList("UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ? AND revoked_at IS NULL RETURNING id",
                UUID.class, reason, sessionId));
    }

    /**
     * Same as revokeSession, plus a staff_id ownership check — for DELETE /auth/sessions/{id},
     * where the id is client-supplied and otherwise has no ownership check within the tenant.
     * Returns false if the session doesn't exist, is already revoked, or belongs to someone else.
     */
    public boolean revokeSessionOwnedBy(UUID staffId, UUID sessionId, String reason) {
        return revoked(jdbc.queryForList(
                "UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ? AND staff_id = ? AND revoked_at IS NULL RETURNING id",
                UUID.class, reason, sessionId, staffId)).size() == 1;
    }

    /** Logout: every still-active row in the family of the given session. */
    public void revokeFamilyOf(UUID sessionId, String reason) {
        revoked(jdbc.queryForList("UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE revoked_at IS NULL " +
                "AND family_id = (SELECT family_id FROM sessions WHERE id = ?) RETURNING id", UUID.class, reason, sessionId));
    }

    /** Reuse detection: kill every still-active row in the chain, not just the one presented. */
    public void revokeFamily(UUID familyId, String reason) {
        revoked(jdbc.queryForList("UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE family_id = ? AND revoked_at IS NULL RETURNING id",
                UUID.class, reason, familyId));
    }


    private RowMapper<SessionRow> sessionMapper() {
        return (rs, i) -> {
            Timestamp revokedAt = rs.getTimestamp("revoked_at");
            return new SessionRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    UUID.fromString(rs.getString("staff_id")),
                    UUID.fromString(rs.getString("family_id")),
                    rs.getString("token_hash"),
                    rs.getTimestamp("expires_at").toInstant(),
                    revokedAt == null ? null : revokedAt.toInstant(),
                    rs.getString("revoked_reason"),
                    rs.getString("device_label"),
                    rs.getString("ip_address"),
                    rs.getTimestamp("last_seen_at").toInstant());
        };
    }
}
