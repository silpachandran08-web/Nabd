package com.nabd.hms.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.PlatformAuthModels.Operator;
import static com.nabd.hms.platform.PlatformAuthModels.SessionRow;

/**
 * All hand-written SQL for platform-operator auth, master schema. No RLS here at all — unlike
 * staff/sessions (RLS-protected by tenant_id, needing a SECURITY DEFINER bootstrap function for the
 * pre-tenant-known refresh lookup), operators aren't scoped to anything: there's one Nabd, so a plain
 * token_hash lookup is the whole story.
 */
@Repository
class PlatformAuthRepository {

    private final JdbcTemplate jdbc;

    PlatformAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String OPERATOR_COLUMNS =
            "id, name, email, pin_hash, role, status, mfa_enabled, mfa_secret_enc ";

    // ::citext cast is load-bearing — see AuthRepository's class comment for why a bare "= ?"
    // against a citext column silently falls back to case-sensitive text equality via JDBC.
    Optional<Operator> findByEmail(String email) {
        return jdbc.query("SELECT " + OPERATOR_COLUMNS + "FROM master.operators WHERE email = ?::citext",
                operatorMapper(), email
        ).stream().findFirst();
    }

    Optional<Operator> findById(UUID id) {
        return jdbc.query("SELECT " + OPERATOR_COLUMNS + "FROM master.operators WHERE id = ?",
                operatorMapper(), id
        ).stream().findFirst();
    }

    void recordLoginAttempt(UUID operatorId, String email, String ip, boolean succeeded) {
        jdbc.update("INSERT INTO master.login_attempts (operator_id, email, ip_address, succeeded) VALUES (?,?,?::inet,?)",
                operatorId, email, ip, succeeded);
    }

    /** Same "failures since last success" window as AuthRepository's staff equivalent. */
    int countFailedAttemptsSinceLastSuccess(UUID operatorId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM master.login_attempts " +
                        "WHERE operator_id = ? AND succeeded = false " +
                        "AND attempted_at > COALESCE(" +
                        "  (SELECT max(attempted_at) FROM master.login_attempts WHERE operator_id = ? AND succeeded = true)," +
                        "  now() - interval '1 day')",
                Integer.class, operatorId, operatorId
        );
        return count == null ? 0 : count;
    }

    Optional<Instant> lastFailedAttemptAt(UUID operatorId) {
        return jdbc.query(
                "SELECT max(attempted_at) AS t FROM master.login_attempts WHERE operator_id = ? AND succeeded = false",
                (rs, i) -> rs.getTimestamp("t"), operatorId
        ).stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    int countAttemptsFromIpSince(String ip, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM master.login_attempts WHERE ip_address = ?::inet AND attempted_at > ?",
                Integer.class, ip, Timestamp.from(since)
        );
        return count == null ? 0 : count;
    }

    void insertSession(UUID id, UUID operatorId, UUID familyId, String tokenHash,
                        Instant expiresAt, String deviceLabel, String ip, String userAgent) {
        jdbc.update(
                "INSERT INTO master.sessions (id, operator_id, family_id, token_hash, expires_at, device_label, ip_address, user_agent) " +
                        "VALUES (?,?,?,?,?,?,?::inet,?)",
                id, operatorId, familyId, tokenHash, Timestamp.from(expiresAt), deviceLabel, ip, userAgent
        );
    }

    Optional<SessionRow> findSessionByTokenHash(String tokenHash) {
        return jdbc.query(
                "SELECT id, operator_id, family_id, token_hash, expires_at, revoked_at, revoked_reason, device_label, ip_address::text, last_seen_at " +
                        "FROM master.sessions WHERE token_hash = ?",
                sessionMapper(), tokenHash
        ).stream().findFirst();
    }

    List<SessionRow> listActiveSessions(UUID operatorId) {
        return jdbc.query(
                "SELECT id, operator_id, family_id, token_hash, expires_at, revoked_at, revoked_reason, device_label, ip_address::text, last_seen_at " +
                        "FROM master.sessions WHERE operator_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC",
                sessionMapper(), operatorId
        );
    }

    void revokeSession(UUID sessionId, String reason) {
        jdbc.update("UPDATE master.sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ? AND revoked_at IS NULL",
                reason, sessionId);
    }

    /** Same ownership-checked revoke as AuthRepository.revokeSessionOwnedBy — the fix for the session-ownership gap found earlier this session applies here too, from day one. */
    boolean revokeSessionOwnedBy(UUID operatorId, UUID sessionId, String reason) {
        int rows = jdbc.update(
                "UPDATE master.sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ? AND operator_id = ? AND revoked_at IS NULL",
                reason, sessionId, operatorId);
        return rows == 1;
    }

    /** Reuse detection: kill every still-active row in the chain, not just the one presented. */
    void revokeFamily(UUID familyId, String reason) {
        jdbc.update("UPDATE master.sessions SET revoked_at = now(), revoked_reason = ? WHERE family_id = ? AND revoked_at IS NULL",
                reason, familyId);
    }

    private RowMapper<Operator> operatorMapper() {
        return (rs, i) -> new Operator(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("pin_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getBoolean("mfa_enabled"),
                rs.getBytes("mfa_secret_enc"));
    }

    private RowMapper<SessionRow> sessionMapper() {
        return (rs, i) -> {
            Timestamp revokedAt = rs.getTimestamp("revoked_at");
            return new SessionRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("operator_id")),
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
