package com.nabd.hms.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.auth.AuthModels.Role;
import static com.nabd.hms.auth.AuthModels.SessionRow;
import static com.nabd.hms.auth.AuthModels.Staff;
import static com.nabd.hms.auth.AuthModels.Tenant;

/**
 * All hand-written SQL for the login flow. RLS is enabled on staff/roles/
 * sessions, so every call site that needs one of them must call
 * TenantContext.set(tenantId) first, in the same transaction — that's what
 * actually enforces tenant isolation at the data layer (defense in depth
 * alongside the explicit tenant_id = ? filters below).
 *
 * Every comparison against a citext column (slug, email, mobile_phone) below casts its bound
 * parameter with ::citext. Without it, a JDBC-bound "?" is sent as plain text, and Postgres falls
 * back to text's case-SENSITIVE equality instead of citext's — the column being citext alone does
 * NOT make the comparison case-insensitive. Found via the Owner login feature (which was the first
 * code path exercised with a genuinely mixed-case value); staff/tenant lookups were already
 * exposed to the same gap, just masked in practice by callers normalizing to lowercase first.
 */
@Repository
class AuthRepository {

    private final JdbcTemplate jdbc;

    AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Tenant> findTenantBySlug(String slug) {
        return jdbc.query(
                "SELECT id, slug, status FROM tenants WHERE slug = ?::citext",
                tenantMapper(), slug
        ).stream().findFirst();
    }

    private static final String STAFF_COLUMNS =
            "id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status, scope, " +
                    "email_verified, mobile_verified, mfa_enabled, mfa_secret_enc ";

    Optional<Staff> findStaffByEmail(UUID tenantId, String email) {
        return jdbc.query("SELECT " + STAFF_COLUMNS + "FROM staff WHERE tenant_id = ? AND email = ?::citext",
                staffMapper(), tenantId, email
        ).stream().findFirst();
    }

    Optional<Staff> findStaffByMobile(UUID tenantId, String mobilePhone) {
        return jdbc.query("SELECT " + STAFF_COLUMNS + "FROM staff WHERE tenant_id = ? AND mobile_phone = ?::citext",
                staffMapper(), tenantId, mobilePhone
        ).stream().findFirst();
    }

    Optional<Staff> findStaffById(UUID staffId) {
        return jdbc.query("SELECT " + STAFF_COLUMNS + "FROM staff WHERE id = ?",
                staffMapper(), staffId
        ).stream().findFirst();
    }

    /** WhatsApp OTP request — hash stored, 5-minute expiry, mirrors the invite-token pattern. */
    void setWhatsAppOtp(UUID staffId, String otpHash, Instant expiresAt) {
        jdbc.update("UPDATE staff SET whatsapp_otp_hash = ?, whatsapp_otp_expires_at = ? WHERE id = ?",
                otpHash, Timestamp.from(expiresAt), staffId);
    }

    /**
     * Atomically validates and consumes the OTP in one statement: the WHERE clause is the check,
     * so there's no read-then-clear race, and no RETURNING-sees-the-post-update-row trap (Postgres
     * evaluates RETURNING against the new row, so comparing the just-nulled hash would always miss).
     */
    boolean consumeWhatsAppOtp(UUID staffId, String otpHash) {
        int rows = jdbc.update(
                "UPDATE staff SET whatsapp_otp_hash = NULL, whatsapp_otp_expires_at = NULL " +
                        "WHERE id = ? AND whatsapp_otp_hash = ? AND whatsapp_otp_expires_at > now()",
                staffId, otpHash);
        return rows == 1;
    }

    void markMobileVerified(UUID staffId) {
        jdbc.update("UPDATE staff SET mobile_verified = true WHERE id = ?", staffId);
    }

    // ── MFA enrollment (NB-042) ──────────────────────────────────────────

    /** Stores the secret but does NOT flip mfa_enabled — that only happens once confirm() verifies a real code. */
    void setPendingMfaSecret(UUID staffId, byte[] encryptedSecret) {
        jdbc.update("UPDATE staff SET mfa_secret_enc = ? WHERE id = ?", encryptedSecret, staffId);
    }

    void enableMfa(UUID staffId) {
        jdbc.update("UPDATE staff SET mfa_enabled = true WHERE id = ?", staffId);
    }

    void insertRecoveryCode(UUID tenantId, UUID staffId, String codeHash) {
        jdbc.update("INSERT INTO mfa_recovery_codes (tenant_id, staff_id, code_hash) VALUES (?,?,?)",
                tenantId, staffId, codeHash);
    }

    /** Atomic check-and-consume — same shape as consumeWhatsAppOtp, so a code can never be used twice. */
    boolean consumeRecoveryCode(UUID staffId, String codeHash) {
        int rows = jdbc.update(
                "UPDATE mfa_recovery_codes SET used_at = now() WHERE staff_id = ? AND code_hash = ? AND used_at IS NULL",
                staffId, codeHash);
        return rows == 1;
    }

    // ── PIN reset (NB-046) ───────────────────────────────────────────────

    void setPinResetToken(UUID staffId, String tokenHash, Instant expiresAt) {
        jdbc.update("UPDATE staff SET pin_reset_token_hash = ?, pin_reset_expires_at = ? WHERE id = ?",
                tokenHash, Timestamp.from(expiresAt), staffId);
    }

    /** Atomic check-and-consume, then set the new PIN in the same statement — no read-then-write race. */
    boolean consumePinResetToken(UUID staffId, String tokenHash, String newPinHash) {
        int rows = jdbc.update(
                "UPDATE staff SET pin_hash = ?, pin_reset_token_hash = NULL, pin_reset_expires_at = NULL " +
                        "WHERE id = ? AND pin_reset_token_hash = ? AND pin_reset_expires_at > now()",
                newPinHash, staffId, tokenHash);
        return rows == 1;
    }

    // ── break-glass (NB-048) ─────────────────────────────────────────────

    UUID insertBreakGlass(UUID tenantId, UUID staffId, String reason, Instant expiresAt) {
        return jdbc.queryForObject(
                "INSERT INTO break_glass_grants (tenant_id, staff_id, reason, expires_at) VALUES (?,?,?,?) RETURNING id",
                UUID.class, tenantId, staffId, reason, Timestamp.from(expiresAt));
    }

    boolean hasActiveBreakGlass(UUID staffId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM break_glass_grants WHERE staff_id = ? AND deactivated_at IS NULL AND expires_at > now()",
                Integer.class, staffId);
        return count != null && count > 0;
    }

    /** Same "detect and close on next read" shape as delegation expiry — see AuthService.mintTokenPair. */
    List<UUID> expireStaleBreakGlass(UUID staffId) {
        return jdbc.query(
                "UPDATE break_glass_grants SET deactivated_at = expires_at, deactivated_reason = 'expired' " +
                        "WHERE staff_id = ? AND deactivated_at IS NULL AND expires_at <= now() RETURNING id",
                (rs, i) -> UUID.fromString(rs.getString("id")), staffId);
    }

    void deactivateBreakGlass(UUID tenantId, UUID id) {
        jdbc.update("UPDATE break_glass_grants SET deactivated_at = now(), deactivated_reason = 'manual' " +
                "WHERE tenant_id = ? AND id = ? AND deactivated_at IS NULL", tenantId, id);
    }

    List<com.nabd.hms.auth.AuthModels.BreakGlassRow> listActiveBreakGlass(UUID tenantId) {
        return jdbc.query(
                "SELECT g.id, g.staff_id, s.name AS staff_name, g.reason, g.activated_at, g.expires_at " +
                        "FROM break_glass_grants g JOIN staff s ON s.id = g.staff_id " +
                        "WHERE g.tenant_id = ? AND g.deactivated_at IS NULL AND g.expires_at > now() " +
                        "ORDER BY g.activated_at DESC",
                (rs, i) -> new com.nabd.hms.auth.AuthModels.BreakGlassRow(
                        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("staff_id")),
                        rs.getString("staff_name"), rs.getString("reason"),
                        rs.getTimestamp("activated_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
                tenantId);
    }

    Optional<Role> findRole(UUID roleId) {
        return jdbc.query(
                "SELECT id, tenant_id, name, grants::text AS grants_json, mfa_required FROM roles WHERE id = ?",
                roleMapper(), roleId
        ).stream().findFirst();
    }

    /** NB-048: break-glass elevates to whatever the tenant's built-in Owner role currently grants. */
    Optional<Role> findBuiltInOwnerRole(UUID tenantId) {
        return jdbc.query(
                "SELECT id, tenant_id, name, grants::text AS grants_json, mfa_required FROM roles WHERE tenant_id = ? AND built_in = true",
                roleMapper(), tenantId
        ).stream().findFirst();
    }

    /**
     * NB-057: roles on loan to this staff member right now, via role_delegations — a narrow direct
     * query into the staff/roles module's table rather than injecting RoleRepository across package
     * boundaries (same convention as CheckoutRepository reading pharmacy/procedure_orders directly).
     */
    List<Role> findActiveDelegatedRoles(UUID staffId) {
        return jdbc.query(
                "SELECT r.id, r.tenant_id, r.name, r.grants::text AS grants_json, r.mfa_required " +
                        "FROM role_delegations d JOIN roles r ON r.id = d.delegated_role_id " +
                        "WHERE d.staff_id = ? AND d.revoked_at IS NULL AND d.starts_at <= now() AND d.expires_at > now()",
                roleMapper(), staffId);
    }

    /**
     * Closes out every delegation for this staff member that has passed its expires_at but was
     * never revoked, returning the ids just closed — the caller audits exactly those. No scheduler
     * exists to do this the instant a delegation lapses, so the next login/refresh (at most one
     * access-token TTL later) is what notices and logs it — same shape as the lockout counter.
     */
    List<UUID> expireStaleDelegations(UUID staffId) {
        return jdbc.query(
                "UPDATE role_delegations SET revoked_at = expires_at, revoked_reason = 'expired' " +
                        "WHERE staff_id = ? AND revoked_at IS NULL AND expires_at <= now() RETURNING id",
                (rs, i) -> UUID.fromString(rs.getString("id")), staffId);
    }

    void recordLoginAttempt(UUID tenantId, UUID staffId, String email, String ip, boolean succeeded) {
        jdbc.update(
                "INSERT INTO login_attempts (tenant_id, staff_id, email, ip_address, succeeded) VALUES (?,?,?,?::inet,?)",
                tenantId, staffId, email, ip, succeeded
        );
    }

    /** Failed attempts since the account's last success — the lockout window, no separate counter column needed. */
    int countFailedAttemptsSinceLastSuccess(UUID staffId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM login_attempts " +
                        "WHERE staff_id = ? AND succeeded = false " +
                        "AND attempted_at > COALESCE(" +
                        "  (SELECT max(attempted_at) FROM login_attempts WHERE staff_id = ? AND succeeded = true)," +
                        "  now() - interval '1 day')",
                Integer.class, staffId, staffId
        );
        return count == null ? 0 : count;
    }

    Optional<Instant> lastFailedAttemptAt(UUID staffId) {
        return jdbc.query(
                "SELECT max(attempted_at) AS t FROM login_attempts WHERE staff_id = ? AND succeeded = false",
                (rs, i) -> rs.getTimestamp("t"), staffId
        ).stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    int countAttemptsFromIpSince(String ip, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM login_attempts WHERE ip_address = ?::inet AND attempted_at > ?",
                Integer.class, ip, Timestamp.from(since)
        );
        return count == null ? 0 : count;
    }

    void insertSession(UUID id, UUID tenantId, UUID staffId, UUID familyId, String tokenHash,
                        Instant expiresAt, String deviceLabel, String ip, String userAgent) {
        jdbc.update(
                "INSERT INTO sessions (id, tenant_id, staff_id, family_id, token_hash, expires_at, device_label, ip_address, user_agent) " +
                        "VALUES (?,?,?,?,?,?,?,?::inet,?)",
                id, tenantId, staffId, familyId, tokenHash, Timestamp.from(expiresAt), deviceLabel, ip, userAgent
        );
    }

    /**
     * Bootstrap lookup — runs before the tenant (and therefore RLS context) is known, via the
     * SECURITY DEFINER function defined in V1__staff_auth_core.sql. Every other session query in
     * this class goes through plain RLS-scoped SQL once TenantContext.set has been called.
     */
    Optional<SessionRow> findSessionByTokenHash(String tokenHash) {
        return jdbc.query(
                "SELECT id, tenant_id, staff_id, family_id, token_hash, expires_at, revoked_at, revoked_reason, device_label, ip_address::text, last_seen_at " +
                        "FROM find_session_by_token_hash(?)",
                sessionMapper(), tokenHash
        ).stream().findFirst();
    }

    List<SessionRow> listActiveSessions(UUID staffId) {
        return jdbc.query(
                "SELECT id, tenant_id, staff_id, family_id, token_hash, expires_at, revoked_at, revoked_reason, device_label, ip_address::text, last_seen_at " +
                        "FROM sessions WHERE staff_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC",
                sessionMapper(), staffId
        );
    }

    /** NB-043: is this specific session still live? — the check that makes a revoke take effect on the very next request, not just at the access token's natural (15 min) expiry. */
    boolean isSessionActive(UUID tenantId, UUID sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL AND expires_at > now()",
                Integer.class, tenantId, sessionId);
        return count != null && count > 0;
    }

    void revokeSession(UUID sessionId, String reason) {
        jdbc.update("UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ? AND revoked_at IS NULL",
                reason, sessionId);
    }

    /**
     * Same as revokeSession, plus a staff_id ownership check — for DELETE /auth/sessions/{id},
     * where the id is client-supplied and otherwise has no ownership check within the tenant.
     * Returns false if the session doesn't exist, is already revoked, or belongs to someone else.
     */
    boolean revokeSessionOwnedBy(UUID staffId, UUID sessionId, String reason) {
        int rows = jdbc.update(
                "UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ? AND staff_id = ? AND revoked_at IS NULL",
                reason, sessionId, staffId);
        return rows == 1;
    }

    /** Reuse detection: kill every still-active row in the chain, not just the one presented. */
    void revokeFamily(UUID familyId, String reason) {
        jdbc.update("UPDATE sessions SET revoked_at = now(), revoked_reason = ? WHERE family_id = ? AND revoked_at IS NULL",
                reason, familyId);
    }

    private RowMapper<Tenant> tenantMapper() {
        return (rs, i) -> new Tenant(
                UUID.fromString(rs.getString("id")), rs.getString("slug"), rs.getString("status"));
    }

    private RowMapper<Staff> staffMapper() {
        return (rs, i) -> new Staff(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("role_id")),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("mobile_phone"),
                rs.getString("pin_hash"),
                rs.getString("status"),
                rs.getString("scope"),
                rs.getBoolean("email_verified"),
                rs.getBoolean("mobile_verified"),
                rs.getBoolean("mfa_enabled"),
                rs.getBytes("mfa_secret_enc"));
    }

    private RowMapper<Role> roleMapper() {
        return (rs, i) -> new Role(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("grants_json"),
                rs.getBoolean("mfa_required"));
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
