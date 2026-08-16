package com.nabd.hms.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.auth.dto.LoginRequest;
import com.nabd.hms.auth.dto.MfaChallengeResponse;
import com.nabd.hms.auth.dto.MfaVerifyRequest;
import com.nabd.hms.auth.dto.RefreshRequest;
import com.nabd.hms.auth.dto.SessionResponse;
import com.nabd.hms.auth.dto.StepUpTokenResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.dto.OtpRequestRequest;
import com.nabd.hms.auth.dto.OtpVerifyRequest;
import com.nabd.hms.common.AesGcmCipher;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.WhatsAppOtpSender;
import com.nabd.hms.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.auth.AuthModels.Role;
import static com.nabd.hms.auth.AuthModels.SessionRow;
import static com.nabd.hms.auth.AuthModels.Staff;
import static com.nabd.hms.auth.AuthModels.Tenant;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String ISSUER = "https://api.nabd.health";

    private final AuthRepository repo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final TotpService totpService;
    private final AesGcmCipher cipher;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final AuthProperties props;
    private final ObjectMapper objectMapper;
    private final WhatsAppOtpSender otpSender;
    private final SecureRandom random = new SecureRandom();

    AuthService(AuthRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder,
                TotpService totpService, AesGcmCipher cipher, JwtEncoder jwtEncoder, JwtDecoder jwtDecoder,
                AuthProperties props, ObjectMapper objectMapper, WhatsAppOtpSender otpSender) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.totpService = totpService;
        this.cipher = cipher;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.props = props;
        this.objectMapper = objectMapper;
        this.otpSender = otpSender;
    }

    // noRollbackFor: recordLoginAttempt() below is the lockout counter's only durable state, and
    // every failing branch writes one immediately before throwing — Spring's default rollback-on-
    // RuntimeException would silently undo that write and permanently disable lockout.
    @Transactional(noRollbackFor = ApiException.class)
    public Object login(LoginRequest req, String ip, String device, String userAgent) {
        enforceIpRateLimit(ip);

        Tenant tenant = repo.findTenantBySlug(req.tenantSlug())
                .filter(t -> !"offboarded".equals(t.status()) && !"suspended".equals(t.status()))
                .orElseThrow(this::invalidCredentials);

        tenantContext.set(tenant.id());
        String email = req.email().toLowerCase();

        Staff staff = repo.findStaffByEmail(tenant.id(), email).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            repo.recordLoginAttempt(tenant.id(), staff == null ? null : staff.id(), email, ip, false);
            throw invalidCredentials();
        }

        enforceAccountLockout(staff);

        if (staff.pinHash() == null || !pinEncoder.matches(req.pin(), staff.pinHash())) {
            repo.recordLoginAttempt(tenant.id(), staff.id(), email, ip, false);
            throw invalidCredentials();
        }
        repo.recordLoginAttempt(tenant.id(), staff.id(), email, ip, true);
        log.info("staff {} authenticated via PIN (tenant {})", staff.id(), tenant.id());

        if (staff.mfaEnabled()) {
            return issueMfaChallenge(staff);
        }
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    /** No enumeration: silently no-ops for an unknown tenant/mobile, but still returns as if sent. */
    @Transactional(noRollbackFor = ApiException.class)
    public void requestOtp(OtpRequestRequest req, String ip) {
        enforceIpRateLimit(ip);

        Tenant tenant = repo.findTenantBySlug(req.tenantSlug())
                .filter(t -> !"offboarded".equals(t.status()) && !"suspended".equals(t.status()))
                .orElse(null);
        if (tenant == null) {
            log.debug("OTP requested for unknown/inactive tenant slug from {}", ip);
            return;
        }
        tenantContext.set(tenant.id());

        Staff staff = repo.findStaffByMobile(tenant.id(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            log.debug("OTP requested for unresolved mobile in tenant {} from {}", tenant.id(), ip);
            return;
        }

        String code = generateOtpCode();
        repo.setWhatsAppOtp(staff.id(), OpaqueTokens.sha256Hex(code), Instant.now().plus(5, ChronoUnit.MINUTES));
        otpSender.send(req.mobilePhone(), code);
        log.info("WhatsApp OTP issued to staff {} (tenant {})", staff.id(), tenant.id());
    }

    // noRollbackFor: recordLoginAttempt()/markMobileVerified() must survive the ApiException thrown
    // on a bad code, same reasoning as login()'s lockout counter.
    @Transactional(noRollbackFor = ApiException.class)
    public Object verifyOtp(OtpVerifyRequest req, String ip, String device, String userAgent) {
        enforceIpRateLimit(ip);

        Tenant tenant = repo.findTenantBySlug(req.tenantSlug())
                .filter(t -> !"offboarded".equals(t.status()) && !"suspended".equals(t.status()))
                .orElseThrow(this::invalidCredentials);
        tenantContext.set(tenant.id());

        Staff staff = repo.findStaffByMobile(tenant.id(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            throw invalidCredentials();
        }

        enforceAccountLockout(staff);

        if (!repo.consumeWhatsAppOtp(staff.id(), OpaqueTokens.sha256Hex(req.code()))) {
            repo.recordLoginAttempt(tenant.id(), staff.id(), staff.email(), ip, false);
            throw invalidCredentials();
        }
        repo.recordLoginAttempt(tenant.id(), staff.id(), staff.email(), ip, true);
        repo.markMobileVerified(staff.id());
        log.info("staff {} authenticated via WhatsApp OTP, mobile verified (tenant {})", staff.id(), tenant.id());

        if (staff.mfaEnabled()) {
            return issueMfaChallenge(staff);
        }
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    private String generateOtpCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    @Transactional
    public TokenPairResponse verifyMfa(MfaVerifyRequest req, String ip, String device, String userAgent) {
        Jwt challenge;
        try {
            challenge = jwtDecoder.decode(req.challengeId());
        } catch (JwtException e) {
            throw mfaFailed(); // covers expired (5 min TTL) and tampered tokens alike
        }
        if (!"mfa_pending".equals(challenge.getClaimAsString("purpose"))) {
            throw mfaFailed();
        }

        // tenantId travels in the challenge claims (known at issue time) so this lookup can set
        // RLS context *before* touching the tenant-scoped staff table — findStaffById alone can't
        // resolve a tenant to scope by, since staff is behind the same RLS it would need to bypass.
        tenantContext.set(UUID.fromString(challenge.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(UUID.fromString(challenge.getSubject())).orElseThrow(this::mfaFailed);

        if (staff.mfaSecretEnc() == null) {
            throw mfaFailed(); // enrolled=false but flagged enabled — never silently skip the check
        }
        byte[] secret = cipher.decrypt(staff.mfaSecretEnc());
        if (!totpService.verify(secret, req.code(), Instant.now())) {
            log.warn("MFA verification failed for staff {}", staff.id());
            throw mfaFailed();
        }
        log.info("staff {} completed MFA", staff.id());
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    /**
     * The "already authenticated, prove MFA again" branch of /auth/mfa/verify — no challenge JWT
     * involved, since the caller's own access token already establishes who they are; the TOTP
     * code is checked directly against their own secret.
     */
    @Transactional
    public StepUpTokenResponse issueStepUpToken(Jwt callerJwt, String code) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::mfaFailed);

        if (staff.mfaSecretEnc() == null) {
            throw mfaFailed();
        }
        byte[] secret = cipher.decrypt(staff.mfaSecretEnc());
        if (!totpService.verify(secret, code, Instant.now())) {
            log.warn("step-up denied for staff {} — TOTP mismatch", staffId);
            throw mfaFailed();
        }

        Instant now = Instant.now();
        Instant exp = now.plusSeconds(300);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(staffId.toString())
                .claim("purpose", "step_up")
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        log.info("staff {} obtained a step-up token", staffId);
        return new StepUpTokenResponse(token, 300);
    }

    /** Called from StaffController right after a successful invite-accept — lands the new staff member logged in. */
    @Transactional
    public TokenPairResponse issueTokensForStaff(UUID tenantId, UUID staffId, String ip, String device, String userAgent) {
        tenantContext.set(tenantId);
        Staff staff = repo.findStaffById(staffId)
                .orElseThrow(() -> new IllegalStateException("staff " + staffId + " vanished right after invite accept"));
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    // noRollbackFor: reuse detection's revokeFamily() write must survive the ApiException thrown
    // right after it, or the default rollback-on-RuntimeException undoes the very revocation this
    // method exists to perform.
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPairResponse refresh(RefreshRequest req, String ip, String device, String userAgent) {
        String tokenHash = OpaqueTokens.sha256Hex(req.refreshToken());
        SessionRow session = repo.findSessionByTokenHash(tokenHash).orElseThrow(this::invalidRefresh);

        tenantContext.set(session.tenantId());

        if (session.revokedAt() != null) {
            // token already used once (or already revoked) and is being presented again — compromise signal
            log.warn("refresh-token reuse detected — revoking session family {} (staff {}, tenant {})",
                    session.familyId(), session.staffId(), session.tenantId());
            repo.revokeFamily(session.familyId(), "reuse_detected");
            throw invalidRefresh();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            throw invalidRefresh();
        }

        Staff staff = repo.findStaffById(session.staffId()).orElseThrow(this::invalidRefresh);
        if (!"active".equals(staff.status())) {
            throw invalidRefresh();
        }

        repo.revokeSession(session.id(), "rotated");
        log.debug("session refreshed for staff {} (family {})", staff.id(), session.familyId());
        return mintTokenPair(staff, session.familyId(), ip, device, userAgent);
    }

    @Transactional
    public void logout(UUID tenantId, UUID sessionId) {
        tenantContext.set(tenantId);
        repo.revokeSession(sessionId, "logout");
        log.info("staff logged out, session {}", sessionId);
    }

    @Transactional
    public List<SessionResponse> listSessions(UUID tenantId, UUID staffId, UUID currentSessionId) {
        tenantContext.set(tenantId);
        return repo.listActiveSessions(staffId).stream()
                .map(s -> new SessionResponse(s.id(), s.deviceLabel(), s.ipAddress(), s.lastSeenAt(), s.id().equals(currentSessionId)))
                .toList();
    }

    /** staffId is the caller, not a path parameter — DELETE /auth/sessions/{id} only ever revokes the caller's own device. */
    @Transactional
    public void revokeSession(UUID tenantId, UUID staffId, UUID sessionId) {
        tenantContext.set(tenantId);
        if (!repo.revokeSessionOwnedBy(staffId, sessionId, "admin_revoke")) {
            throw notFound();
        }
        log.info("staff {} revoked their own session {}", staffId, sessionId);
    }

    // ---- internals ----

    private void enforceIpRateLimit(String ip) {
        int recent = repo.countAttemptsFromIpSince(ip, Instant.now().minus(1, ChronoUnit.MINUTES));
        if (recent >= props.rateLimitPerIpPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many attempts", "Try again shortly.");
        }
    }

    // Deliberately does NOT record a new failed attempt for a still-locked hit: doing so (the
    // previous behavior) re-bases lockedUntil off "now" on every single request during the lock
    // window, so a client polling faster than the backoff duration renews the lock forever — a
    // trivial, unauthenticated (no correct PIN needed) permanent lockout of any known email. The
    // counter should only grow from genuine post-expiry credential checks (see login(), which
    // records real successes/failures after this gate).
    private void enforceAccountLockout(Staff staff) {
        int failures = repo.countFailedAttemptsSinceLastSuccess(staff.id());
        if (failures < props.lockoutThreshold()) {
            return;
        }
        Instant lastFailedAt = repo.lastFailedAttemptAt(staff.id()).orElse(Instant.EPOCH);
        Instant lockedUntil = lastFailedAt.plus(backoffDuration(failures));
        if (Instant.now().isBefore(lockedUntil)) {
            log.warn("account locked: staff {} has {} failed attempts, locked until {}", staff.id(), failures, lockedUntil);
            throw accountLocked();
        }
    }

    private Duration backoffDuration(int failureCount) {
        int overBy = failureCount - props.lockoutThreshold() + 1; // 1, 2, 3, ...
        long minutes = Math.min(30, 1L << (overBy - 1)); // 1, 2, 4, 8, 16, 30(cap), ...
        return Duration.ofMinutes(minutes);
    }

    private MfaChallengeResponse issueMfaChallenge(Staff staff) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.mfaChallengeTtlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(staff.id().toString())
                .claim("purpose", "mfa_pending")
                .claim("tenantId", staff.tenantId().toString())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new MfaChallengeResponse(token, "totp", props.mfaChallengeTtlMinutes() * 60L);
    }

    private TokenPairResponse mintTokenPair(Staff staff, UUID familyId, String ip, String device, String userAgent) {
        Role role = repo.findRole(staff.roleId())
                .orElseThrow(() -> new IllegalStateException("role missing for staff " + staff.id()));
        List<String> permissions = flattenGrants(role.grantsJson());

        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant accessExp = now.plus(props.accessTokenTtlMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(accessExp)
                .subject(staff.id().toString())
                .claim("tenantId", staff.tenantId().toString())
                .claim("roleId", staff.roleId().toString())
                .claim("sid", sessionId.toString())
                .claim("permissions", permissions)
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        String rawRefresh = OpaqueTokens.generate();
        Instant refreshExp = now.plus(props.refreshTokenTtlDays(), ChronoUnit.DAYS);
        repo.insertSession(sessionId, staff.tenantId(), staff.id(), familyId,
                OpaqueTokens.sha256Hex(rawRefresh), refreshExp, device, ip, userAgent);

        return new TokenPairResponse(accessToken, rawRefresh, Duration.between(now, accessExp).toSeconds());
    }

    private List<String> flattenGrants(String grantsJson) {
        try {
            List<ModuleGrant> grants = objectMapper.readValue(grantsJson, new TypeReference<List<ModuleGrant>>() {
            });
            return GrantsFlattener.flatten(grants);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("malformed grants JSON", e);
        }
    }


    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
                "Email/mobile or credential is incorrect.");
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
