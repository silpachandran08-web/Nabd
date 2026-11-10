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

/** PLT-034 (split from AuthRepository): one-time codes (WhatsApp OTP, recovery codes, PIN reset), pending MFA secrets and
 * the attempt logs that drive lockout and the per-IP rate limit. Every consume is an atomic check-and-update. */
@Repository
public class CredentialRepository {

    private final JdbcTemplate jdbc;

    CredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** WhatsApp OTP request — hash stored, 5-minute expiry, mirrors the invite-token pattern. */
    public void setWhatsAppOtp(UUID staffId, String otpHash, Instant expiresAt) {
        jdbc.update("UPDATE staff SET whatsapp_otp_hash = ?, whatsapp_otp_expires_at = ? WHERE id = ?",
                otpHash, Timestamp.from(expiresAt), staffId);
    }

    /**
     * Atomically validates and consumes the OTP in one statement: the WHERE clause is the check,
     * so there's no read-then-clear race, and no RETURNING-sees-the-post-update-row trap (Postgres
     * evaluates RETURNING against the new row, so comparing the just-nulled hash would always miss).
     */
    public boolean consumeWhatsAppOtp(UUID staffId, String otpHash) {
        int rows = jdbc.update(
                "UPDATE staff SET whatsapp_otp_hash = NULL, whatsapp_otp_expires_at = NULL " +
                        "WHERE id = ? AND whatsapp_otp_hash = ? AND whatsapp_otp_expires_at > now()",
                staffId, otpHash);
        return rows == 1;
    }

    public void markMobileVerified(UUID staffId) {
        jdbc.update("UPDATE staff SET mobile_verified = true WHERE id = ?", staffId);
    }

    // ── MFA enrollment (NB-042) ──────────────────────────────────────────

    /** PLT-004: parks the new secret beside the active one; mfa_secret_enc / mfa_enabled only change in confirmPendingMfa. */
    public void setPendingMfaSecret(UUID staffId, byte[] encryptedSecret) {
        jdbc.update("UPDATE staff SET mfa_pending_secret_enc = ?, mfa_pending_created_at = now() WHERE id = ?", encryptedSecret, staffId);
    }

    /** Pending becomes active, MFA on, a pending admin reset is satisfied. */
    public void confirmPendingMfa(UUID staffId) {
        jdbc.update("UPDATE staff SET mfa_secret_enc = mfa_pending_secret_enc, mfa_pending_secret_enc = NULL, "
                + "mfa_pending_created_at = NULL, mfa_enabled = true, mfa_reset_required = false WHERE id = ?", staffId);
    }

    /** Old unused codes stop working when a new set is issued. */
    public void revokeUnusedRecoveryCodes(UUID staffId) {
        jdbc.update("UPDATE mfa_recovery_codes SET revoked_at = now() WHERE staff_id = ? AND used_at IS NULL AND revoked_at IS NULL", staffId);
    }

    public int countLiveRecoveryCodes(UUID staffId) {
        return jdbc.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE staff_id = ? AND used_at IS NULL AND revoked_at IS NULL",
                Integer.class, staffId);
    }

    public void insertRecoveryCode(UUID tenantId, UUID staffId, String codeHash) {
        jdbc.update("INSERT INTO mfa_recovery_codes (tenant_id, staff_id, code_hash) VALUES (?,?,?)",
                tenantId, staffId, codeHash);
    }

    /** Atomic check-and-consume — same shape as consumeWhatsAppOtp, so a code can never be used twice. */
    public boolean consumeRecoveryCode(UUID staffId, String codeHash) {
        int rows = jdbc.update(
                "UPDATE mfa_recovery_codes SET used_at = now() WHERE staff_id = ? AND code_hash = ? AND used_at IS NULL AND revoked_at IS NULL",
                staffId, codeHash);
        return rows == 1;
    }

    // ── PIN reset (NB-046) ───────────────────────────────────────────────

    public void setPinResetToken(UUID staffId, String tokenHash, Instant expiresAt) {
        jdbc.update("UPDATE staff SET pin_reset_token_hash = ?, pin_reset_expires_at = ? WHERE id = ?",
                tokenHash, Timestamp.from(expiresAt), staffId);
    }

    /** Atomic check-and-consume, then set the new PIN in the same statement — no read-then-write race. */
    public boolean consumePinResetToken(UUID staffId, String tokenHash, String newPinHash) {
        int rows = jdbc.update(
                "UPDATE staff SET pin_hash = ?, pin_policy_version = 2, pin_reset_token_hash = NULL, pin_reset_expires_at = NULL " +
                        "WHERE id = ? AND pin_reset_token_hash = ? AND pin_reset_expires_at > now()",
                newPinHash, staffId, tokenHash);
        return rows == 1;
    }

    public void recordLoginAttempt(UUID tenantId, UUID staffId, String email, String ip, boolean succeeded) {
        jdbc.update(
                "INSERT INTO login_attempts (tenant_id, staff_id, email, ip_address, succeeded) VALUES (?,?,?,?::inet,?)",
                tenantId, staffId, email, ip, succeeded
        );
    }

    /** Failed attempts since the account's last success — the lockout window, no separate counter column needed. */
    public int countFailedAttemptsSinceLastSuccess(UUID staffId) {
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

    /** FIN-018: failed owner-PIN tries by this desk caller since their last good one (1 day at most), as for logins. */
    public int countFailedPinAssertionsSinceLastSuccess(UUID staffId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pin_assertion_attempts WHERE staff_id = ? AND NOT succeeded AND attempted_at > "
                + "COALESCE((SELECT max(attempted_at) FROM pin_assertion_attempts WHERE staff_id = ? AND succeeded), now() - interval '1 day')",
                Integer.class, staffId, staffId);
        return count == null ? 0 : count;
    }

    public Optional<Instant> lastFailedPinAssertionAt(UUID staffId) {
        return jdbc.query("SELECT max(attempted_at) AS t FROM pin_assertion_attempts WHERE staff_id = ? AND NOT succeeded",
                (rs, i) -> rs.getTimestamp("t"), staffId).stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    public void recordPinAssertionAttempt(UUID tenantId, UUID staffId, boolean succeeded) {
        jdbc.update("INSERT INTO pin_assertion_attempts (tenant_id, staff_id, succeeded) VALUES (?,?,?)", tenantId, staffId, succeeded);
    }

    public Optional<Instant> lastFailedAttemptAt(UUID staffId) {
        return jdbc.query(
                "SELECT max(attempted_at) AS t FROM login_attempts WHERE staff_id = ? AND succeeded = false",
                (rs, i) -> rs.getTimestamp("t"), staffId
        ).stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    public int countAttemptsFromIpSince(String ip, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT login_attempts_count_from_ip(?::inet, ?)",
                Integer.class, ip, Timestamp.from(since)
        );
        return count == null ? 0 : count;
    }
}
