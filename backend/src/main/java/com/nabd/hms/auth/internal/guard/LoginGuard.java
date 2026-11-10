package com.nabd.hms.auth.internal.guard;

import com.nabd.hms.auth.TotpService;

import com.nabd.hms.auth.internal.store.CredentialRepository;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-003: the one place the brute-force rules for every staff credential factor live. Failures of
 * PIN, OTP, TOTP, unlock and step-up all land in login_attempts (tagged by factor) and feed the same
 * exponential, capped, non-renewing lockout; a TOTP time-step can be used once.
 */
@Component
public class LoginGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginGuard.class);

    private final CredentialRepository credentials;
    private final JdbcTemplate jdbc;
    private final TotpService totp;
    private final AuthProperties props;

    private final Clock clock;

    LoginGuard(CredentialRepository credentials, JdbcTemplate jdbc, TotpService totp, AuthProperties props, Clock clock) {
        this.clock = clock;
        this.credentials = credentials;
        this.jdbc = jdbc;
        this.totp = totp;
        this.props = props;
    }

    /** Throws 423 while the backoff window runs. Does not record anything, so a locked hit cannot renew the lock. */
    public void enforceLockout(UUID staffId) {
        enforceLockout(credentials.countFailedAttemptsSinceLastSuccess(staffId), credentials.lastFailedAttemptAt(staffId), staffId);
    }

    /** The same rule over another attempt log (FIN-018: owner PIN tries at a desk, counted per desk caller). */
    public void enforceLockout(int failures, java.util.Optional<Instant> lastFailed, UUID staffId) {
        if (failures < props.lockoutThreshold()) {
            return;
        }
        Instant lastFailedAt = lastFailed.orElse(Instant.EPOCH);
        Instant lockedUntil = lastFailedAt.plus(backoff(failures));
        Instant now = Instant.now(clock);
        if (now.isBefore(lockedUntil)) {
            log.warn("account locked: staff {} has {} failed attempts, locked until {}", staffId, failures, lockedUntil);
            long retry = Math.max(1, Duration.between(now, lockedUntil).toSeconds());
            throw new ApiException(HttpStatus.LOCKED, "account-locked", "Account locked",
                    "Too many failed attempts. Try again later.", Map.of("retryAfterSeconds", retry));
        }
    }

    private Duration backoff(int failureCount) {
        int overBy = failureCount - props.lockoutThreshold() + 1; // 1, 2, 3, ...
        return Duration.ofMinutes(Math.min(30, 1L << Math.min(overBy - 1, 10))); // 1, 2, 4, ... capped 30 min
    }

    public void recordAttempt(UUID tenantId, UUID staffId, String email, String factor, boolean succeeded) {
        jdbc.update("INSERT INTO login_attempts (tenant_id, staff_id, email, ip_address, succeeded, factor) VALUES (?,?,?,?::inet,?,?)",
                tenantId, staffId, email, currentIp(), succeeded, factor);
    }

    /** Valid code AND its time-step is newer than the last one accepted for this account (atomic claim). */
    public boolean verifyTotp(UUID staffId, byte[] secret, String code) {
        if (secret == null) return false; // PLT-044: secret did not decrypt for this row (FieldCipher logged it)
        long step = totp.matchStep(secret, code, Instant.now(clock));
        return step >= 0 && jdbc.update("UPDATE staff SET mfa_last_used_step = ? WHERE id = ? "
                + "AND (mfa_last_used_step IS NULL OR mfa_last_used_step < ?)", step, staffId, step) == 1;
    }

    public boolean pinChangeRequired(UUID staffId) {
        Integer v = jdbc.query("SELECT pin_policy_version FROM staff WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : 2, staffId);
        return v != null && v < 2;
    }

    /** The request's validated peer address; "0.0.0.0" outside a request. */
    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest http = attrs.getRequest();
            return RequestMeta.clientIp(http);
        }
        return "0.0.0.0";
    }
}
