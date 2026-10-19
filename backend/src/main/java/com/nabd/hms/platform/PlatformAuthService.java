package com.nabd.hms.platform;

import com.nabd.hms.auth.TotpService;
import com.nabd.hms.auth.dto.MfaChallengeResponse;
import com.nabd.hms.auth.dto.MfaVerifyRequest;
import com.nabd.hms.auth.dto.RefreshRequest;
import com.nabd.hms.auth.dto.SessionResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.config.AuthProperties;
import com.nabd.hms.platform.dto.OperatorLoginRequest;
import com.nabd.hms.platform.dto.OperatorProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import com.nabd.hms.shared.security.TokenType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.platform.PlatformAuthModels.Operator;
import static com.nabd.hms.platform.PlatformAuthModels.SessionRow;

/**
 * Platform-operator auth (Super Admin and the other 6 SaaS roles) — a genuinely separate identity
 * domain from clinic staff/Owner, not a reuse. Deliberately mirrors AuthService's proven security
 * properties (lockout that doesn't self-renew, refresh-token rotation + reuse detection revoking the
 * whole family, ownership-checked session revoke) rather than a thin stub — this is the
 * highest-privilege account type in the system, so the same rigor applies, not less.
 */
@Service
public class PlatformAuthService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthService.class);
    private static final String ISSUER = "https://api.nabd.health"; // must match AuthService.ISSUER — same signing key/issuer

    private final PlatformAuthRepository repo;
    private final PasswordEncoder pinEncoder;
    private final TotpService totpService;
    private final FieldCipher cipher;
    private final JwtEncoder jwtEncoder;
    private final ChallengeTokenDecoder jwtDecoder;
    private final AuthProperties props;
    private final PlatformAudit audit;
    private final AuditService auditService;

    PlatformAuthService(PlatformAuthRepository repo, PasswordEncoder pinEncoder, TotpService totpService,
                         FieldCipher cipher, JwtEncoder jwtEncoder, ChallengeTokenDecoder jwtDecoder, AuthProperties props,
                         PlatformAudit audit, AuditService auditService) {
        this.audit = audit;
        this.auditService = auditService;
        this.repo = repo;
        this.pinEncoder = pinEncoder;
        this.totpService = totpService;
        this.cipher = cipher;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.props = props;
    }

    // noRollbackFor: recordLoginAttempt() must survive the ApiException thrown right after it —
    // see AuthService.login() for the rollback gotcha this avoids.
    @Transactional(noRollbackFor = ApiException.class)
    public Object login(OperatorLoginRequest req, String ip, String device, String userAgent) {
        enforceIpRateLimit(ip);
        String email = req.email().toLowerCase();

        Operator operator = repo.findByEmail(email).orElse(null);
        if (operator == null || !"active".equals(operator.status())) {
            repo.recordLoginAttempt(operator == null ? null : operator.id(), email, ip, false);
            if (operator == null) {
                // No email in the row: the typed value may be anything; master.login_attempts keeps it for forensics.
                auditService.recordPlatform("operator", null, "unknown operator", "unknown", ip,
                        "platform_auth.sign_in_failed", "operator", null, null, Map.of("reason", "unknown_account"));
            } else {
                signInFailed(operator, ip, "inactive_account");
            }
            throw invalidCredentials();
        }

        enforceAccountLockout(operator);

        if (operator.pinHash() == null || !pinEncoder.matches(req.pin(), operator.pinHash())) {
            repo.recordLoginAttempt(operator.id(), email, ip, false);
            signInFailed(operator, ip, "invalid_credentials");
            throw invalidCredentials();
        }
        if (!operator.mfaEnabled()) { // with MFA the success is recorded after the second factor (PLT-003)
            repo.recordLoginAttempt(operator.id(), email, ip, true);
        }
        log.info("operator {} authenticated via PIN (role {})", operator.id(), operator.role());

        if (operator.mfaEnabled()) {
            audit.record(null, operator.id(), ip, "platform_auth.mfa_challenged", "operator", operator.id(), null);
            return issueMfaChallenge(operator);
        }
        audit.record(null, operator.id(), ip, "platform_auth.signed_in", "operator", operator.id(), Map.of("mfa", false));
        return mintTokenPair(operator, UUID.randomUUID(), ip, device, userAgent);
    }

    private void signInFailed(Operator operator, String ip, String reason) {
        audit.record(null, operator.id(), ip, "platform_auth.sign_in_failed", "operator", operator.id(),
                Map.of("reason", reason));
    }

    // noRollbackFor: the mfa_failed audit row must survive the ApiException thrown right after it.
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPairResponse verifyMfa(MfaVerifyRequest req, String ip, String device, String userAgent) {
        Jwt challenge;
        try {
            challenge = jwtDecoder.decode(req.challengeId(), TokenType.MFA_PENDING);
        } catch (JwtException e) {
            throw mfaFailed(); // covers expired (5 min TTL) and tampered tokens alike
        }
        if (!"platform_mfa_pending".equals(challenge.getClaimAsString("purpose"))) {
            throw mfaFailed();
        }

        Operator operator = repo.findById(UUID.fromString(challenge.getSubject())).orElseThrow(this::mfaFailed);
        if (operator.mfaSecretEnc() == null) {
            throw mfaFailed();
        }
        enforceAccountLockout(operator);
        byte[] secret = cipher.tryDecrypt(operator.mfaSecretEnc(),
                FieldCipher.Aad.of("platform", "master.operators", "mfa_secret", operator.id())).orElseThrow(this::mfaFailed);
        long step = totpService.matchStep(secret, req.code(), Instant.now());
        if (step < 0 || !repo.claimTotpStep(operator.id(), step)) { // claim = replay guard
            repo.recordLoginAttempt(operator.id(), operator.email(), ip, false);
            log.warn("MFA verification failed for operator {}", operator.id());
            audit.record(null, operator.id(), ip, "platform_auth.mfa_failed", "operator", operator.id(), null);
            throw mfaFailed();
        }
        repo.recordLoginAttempt(operator.id(), operator.email(), ip, true);
        log.info("operator {} completed MFA", operator.id());
        audit.record(null, operator.id(), ip, "platform_auth.signed_in", "operator", operator.id(), Map.of("mfa", true));
        return mintTokenPair(operator, UUID.randomUUID(), ip, device, userAgent);
    }

    // noRollbackFor: reuse detection's revokeFamily() write must survive the ApiException thrown
    // right after it — same reasoning as AuthService.refresh().
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPairResponse refresh(RefreshRequest req, String ip, String device, String userAgent) {
        String tokenHash = OpaqueTokens.sha256Hex(req.refreshToken());
        SessionRow session = repo.findSessionByTokenHash(tokenHash).orElseThrow(this::invalidRefresh);

        if (session.revokedAt() != null) {
            log.warn("refresh-token reuse detected — revoking session family {} (operator {})",
                    session.familyId(), session.operatorId());
            repo.revokeFamily(session.familyId(), "reuse_detected");
            audit.record(null, session.operatorId(), ip, "platform_auth.refresh_reuse_detected", "operator_session",
                    session.familyId(), null);
            throw invalidRefresh();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            throw invalidRefresh();
        }

        Operator operator = repo.findById(session.operatorId()).orElseThrow(this::invalidRefresh);
        if (!"active".equals(operator.status())) {
            throw invalidRefresh();
        }

        repo.revokeSession(session.id(), "rotated");
        log.debug("session refreshed for operator {} (family {})", operator.id(), session.familyId());
        return mintTokenPair(operator, session.familyId(), ip, device, userAgent);
    }

    @Transactional
    public void logout(UUID operatorId, UUID sessionId, String ip) {
        repo.revokeSession(sessionId, "logout");
        audit.record(null, operatorId, ip, "platform_auth.signed_out", "operator_session", sessionId, null);
        log.info("operator logged out, session {}", sessionId);
    }

    public OperatorProfileResponse getProfile(UUID operatorId) {
        Operator operator = repo.findById(operatorId).orElseThrow(this::notFound);
        return new OperatorProfileResponse(operator.id(), operator.name(), operator.email(), operator.role(),
                PlatformPermissions.forRole(operator.role()));
    }

    @Transactional
    public List<SessionResponse> listSessions(UUID operatorId, UUID currentSessionId) {
        return repo.listActiveSessions(operatorId).stream()
                .map(s -> new SessionResponse(s.id(), s.deviceLabel(), s.ipAddress(), s.lastSeenAt(), s.id().equals(currentSessionId)))
                .toList();
    }

    /** staffId-equivalent ownership check applies here too — DELETE only ever revokes the caller's own session. */
    @Transactional
    public void revokeSession(UUID operatorId, UUID sessionId, String ip) {
        if (!repo.revokeSessionOwnedBy(operatorId, sessionId, "admin_revoke")) {
            throw notFound();
        }
        audit.record(null, operatorId, ip, "platform_auth.session_revoked", "operator_session", sessionId, null);
        log.info("operator {} revoked their own session {}", operatorId, sessionId);
    }

    // ---- internals ----

    private void enforceIpRateLimit(String ip) {
        int recent = repo.countAttemptsFromIpSince(ip, Instant.now().minus(1, ChronoUnit.MINUTES));
        if (recent >= props.rateLimitPerIpPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many attempts", "Try again shortly.");
        }
    }

    // Same fix as AuthService.enforceAccountLockout: a still-locked hit does NOT record a new
    // failed attempt, or the lock renews forever under repeated requests.
    private void enforceAccountLockout(Operator operator) {
        int failures = repo.countFailedAttemptsSinceLastSuccess(operator.id());
        if (failures < props.lockoutThreshold()) {
            return;
        }
        Instant lastFailedAt = repo.lastFailedAttemptAt(operator.id()).orElse(Instant.EPOCH);
        Instant lockedUntil = lastFailedAt.plus(backoffDuration(failures));
        if (Instant.now().isBefore(lockedUntil)) {
            log.warn("account locked: operator {} has {} failed attempts, locked until {}", operator.id(), failures, lockedUntil);
            throw accountLocked();
        }
    }

    private Duration backoffDuration(int failureCount) {
        int overBy = failureCount - props.lockoutThreshold() + 1;
        long minutes = Math.min(30, 1L << (overBy - 1));
        return Duration.ofMinutes(minutes);
    }

    private MfaChallengeResponse issueMfaChallenge(Operator operator) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.mfaChallengeTtlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(operator.id().toString())
                .claim("purpose", "platform_mfa_pending");
        TokenType.MFA_PENDING.stamp(claimsB, Audience.PLATFORM_MFA);
        JwtClaimsSet claims = claimsB.build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new MfaChallengeResponse(token, "totp", props.mfaChallengeTtlMinutes() * 60L);
    }

    private TokenPairResponse mintTokenPair(Operator operator, UUID familyId, String ip, String device, String userAgent) {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant accessExp = now.plus(props.accessTokenTtlMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(accessExp)
                .subject(operator.id().toString())
                .claim("role", operator.role())
                .claim("permissions", PlatformPermissions.forRole(operator.role()))
                .claim("sid", sessionId.toString());
        TokenType.ACCESS.stamp(claimsB, Audience.PLATFORM_API);
        JwtClaimsSet claims = claimsB.build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        String rawRefresh = OpaqueTokens.generate();
        Instant refreshExp = now.plus(props.refreshTokenTtlDays(), ChronoUnit.DAYS);
        repo.insertSession(sessionId, operator.id(), familyId,
                OpaqueTokens.sha256Hex(rawRefresh), refreshExp, device, ip, userAgent);

        return new TokenPairResponse(accessToken, rawRefresh, Duration.between(now, accessExp).toSeconds(),
                Duration.between(now, refreshExp).toSeconds());
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
                "Email or PIN is incorrect.");
    }

    private ApiException accountLocked() {
        return new ApiException(HttpStatus.LOCKED, "account-locked", "Account locked",
                "Too many failed attempts. Try again later.");
    }

    private ApiException mfaFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "mfa-failed", "MFA verification failed",
                "The code is incorrect or the challenge has expired.");
    }

    private ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-refresh-token", "Invalid refresh token",
                "Session is no longer valid; sign in again.");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }
}
