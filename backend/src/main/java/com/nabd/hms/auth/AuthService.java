package com.nabd.hms.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.auth.dto.LoginRequest;
import com.nabd.hms.auth.dto.MfaChallengeResponse;
import com.nabd.hms.auth.dto.MfaConfirmRequest;
import com.nabd.hms.auth.dto.MfaConfirmResponse;
import com.nabd.hms.auth.dto.MfaEnrollResponse;
import com.nabd.hms.auth.dto.MfaSetupRequiredResponse;
import com.nabd.hms.auth.dto.MfaVerifyRequest;
import com.nabd.hms.auth.dto.PinResetConfirmRequest;
import com.nabd.hms.auth.dto.PinResetRequestRequest;
import com.nabd.hms.auth.dto.RefreshRequest;
import com.nabd.hms.auth.dto.SessionResponse;
import com.nabd.hms.auth.dto.RecoveryVerifyRequest;
import com.nabd.hms.auth.dto.RecoveryVerifyResponse;
import com.nabd.hms.auth.dto.StepUpTokenResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.dto.OtpRequestRequest;
import com.nabd.hms.auth.dto.OtpVerifyRequest;
import com.nabd.hms.auth.dto.UnlockRequest;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.config.AuthProperties;
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

import java.time.Clock;
import java.time.Duration;
import java.security.SecureRandom;
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
    private final FieldCipher cipher;
    private final JwtEncoder jwtEncoder;
    private final ChallengeTokenDecoder jwtDecoder;
    private final AuthProperties props;
    private final ObjectMapper objectMapper;
    private final OtpOutbox otpOutbox;
    private final AuditService auditService;
    private final LoginGuard guard;
    private final StepUpVerifier stepUpVerifier;
    private final MfaPolicy mfaPolicy;
    private final OtpSendLimiter otpLimiter;
    private final SecureRandom random = new SecureRandom();

    private final Clock clock;

    @org.springframework.beans.factory.annotation.Value("${app.auth.absolute-session-hours:12}")
    private int absoluteSessionHours; // PLT-007: refresh-family cap (one shift); per-tenant SSO policy later

    AuthService(AuthRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder,
                TotpService totpService, FieldCipher cipher, JwtEncoder jwtEncoder, ChallengeTokenDecoder jwtDecoder,
                AuthProperties props, ObjectMapper objectMapper, OtpOutbox otpOutbox, AuditService auditService,
                LoginGuard guard, StepUpVerifier stepUpVerifier, MfaPolicy mfaPolicy, OtpSendLimiter otpLimiter, Clock clock) {
        this.clock = clock;
        this.mfaPolicy = mfaPolicy;
        this.otpLimiter = otpLimiter;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.totpService = totpService;
        this.cipher = cipher;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.props = props;
        this.objectMapper = objectMapper;
        this.otpOutbox = otpOutbox;
        this.auditService = auditService;
        this.guard = guard;
        this.stepUpVerifier = stepUpVerifier;
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
        if (!staff.mfaEnabled()) { // with MFA the success is recorded after the second factor, so PIN logins cannot reset TOTP failures
            repo.recordLoginAttempt(tenant.id(), staff.id(), email, ip, true);
        }
        log.info("staff {} authenticated via PIN (tenant {})", staff.id(), tenant.id());

        if (!staff.mfaEnabled() && mfaRequiredByPolicy(staff)) {
            return issueMfaSetupRequired(staff);
        }
        if (staff.mfaEnabled()) {
            return issueMfaChallenge(staff);
        }
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    /**
     * NB-042: roles.mfa_required is the per-role opt-in. PLT-005 adds an invariant on top that no role
     * edit can clear: the built-in Owner role and any role (own or on loan) holding :approve or
     * :refundDiscount always requires MFA (see MfaPolicy).
     */
    private boolean mfaRequiredByPolicy(Staff staff) {
        if (staff.mfaResetRequired()) { // PLT-004: an owner reset forces enrolment at next sign-in
            return true;
        }
        Role role = repo.findRole(staff.roleId()).orElseThrow(() -> new IllegalStateException("role missing"));
        return role.mfaRequired() || privilegedByPolicy(staff, role);
    }

    private boolean privilegedByPolicy(Staff staff, Role role) {
        if (mfaPolicy.requiredForRole(role.builtIn(), flattenGrants(role.grantsJson()))) {
            return true;
        }
        return repo.findActiveDelegatedRoles(staff.id()).stream()
                .anyMatch(d -> mfaPolicy.requiredForRole(d.builtIn(), flattenGrants(d.grantsJson())));
    }

    private MfaSetupRequiredResponse issueMfaSetupRequired(Staff staff) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(props.mfaChallengeTtlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(staff.id().toString())
                .claim("purpose", "mfa_setup_required")
                .claim("tenantId", staff.tenantId().toString());
        TokenType.MFA_SETUP.stamp(claimsB, Audience.MFA);
        JwtClaimsSet claims = claimsB.build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new MfaSetupRequiredResponse(token, props.mfaChallengeTtlMinutes() * 60L);
    }

    /** PLT-006: no enumeration and no message pumping (see OtpSendLimiter). */
    @Transactional(noRollbackFor = ApiException.class)
    public void requestOtp(OtpRequestRequest req, String ip) {
        enforceIpRateLimit(ip);
        // PLT-006: same 202 for every outcome (unknown clinic or number, limited, sent). The per-number and per-tenant
        // limits are counted whether or not the number exists, and the WhatsApp call happens in the outbox (OPS-012
        // OtpOutbox / OtpRequestedHandler), so neither the response nor its timing shows which numbers are registered.
        Tenant tenant = repo.findTenantBySlug(req.tenantSlug())
                .filter(t -> !"offboarded".equals(t.status()) && !"suspended".equals(t.status()))
                .orElse(null);
        if (tenant == null) {
            return;
        }
        tenantContext.set(tenant.id());
        if (otpLimiter.tryConsume(tenant.id(), PhoneNumbers.digits(req.mobilePhone(), tenant.region())) != OtpSendLimiter.Decision.SEND) {
            return;
        }
        Staff staff = repo.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            return; // PLT-006: same 202 as a real send, so the response does not reveal which numbers exist
        }
        String code = generateOtpCode();
        Instant expiresAt = Instant.now(clock).plus(5, ChronoUnit.MINUTES);
        repo.setWhatsAppOtp(staff.id(), OpaqueTokens.sha256Hex(code), expiresAt);
        otpOutbox.queue(tenant.id(), staff.id(), "login", code, expiresAt); // OPS-012: sent by the outbox, to the number on file
        log.info("WhatsApp OTP issued to staff {} (tenant {})", staff.id(), tenant.id());
    }

    private String generateOtpCode() {
        return String.format("%06d", random.nextInt(1_000_000));
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

        Staff staff = repo.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            throw invalidCredentials();
        }

        enforceAccountLockout(staff);

        if (!repo.consumeWhatsAppOtp(staff.id(), OpaqueTokens.sha256Hex(req.code()))) {
            guard.recordAttempt(tenant.id(), staff.id(), staff.email(), "otp", false);
            throw invalidCredentials();
        }
        if (!staff.mfaEnabled()) {
            guard.recordAttempt(tenant.id(), staff.id(), staff.email(), "otp", true);
        }
        repo.markMobileVerified(staff.id());
        log.info("staff {} authenticated via WhatsApp OTP, mobile verified (tenant {})", staff.id(), tenant.id());

        if (!staff.mfaEnabled() && mfaRequiredByPolicy(staff)) {
            return issueMfaSetupRequired(staff);
        }
        if (staff.mfaEnabled()) {
            return issueMfaChallenge(staff);
        }
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    @Transactional(noRollbackFor = ApiException.class) // the failed-attempt row must survive the 401
    public TokenPairResponse verifyMfa(MfaVerifyRequest req, String ip, String device, String userAgent) {
        Jwt challenge;
        try {
            challenge = jwtDecoder.decode(req.challengeId(), TokenType.MFA_PENDING);
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
        guard.enforceLockout(staff.id());
        byte[] secret = cipher.tryDecrypt(staff.mfaSecretEnc(), mfaAad(staff)).orElseThrow(this::mfaFailed);
        if (!guard.verifyTotp(staff.id(), secret, req.code())) {
            log.warn("MFA verification failed for staff {}", staff.id());
            guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "totp", false);
            throw mfaFailed();
        }
        guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "totp", true);
        log.info("staff {} completed MFA", staff.id());
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    /**
     * The "already authenticated, prove MFA again" branch of /auth/mfa/verify — no challenge JWT
     * involved, since the caller's own access token already establishes who they are; the TOTP
     * code is checked directly against their own secret.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public StepUpTokenResponse issueStepUpToken(Jwt callerJwt, String code) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::mfaFailed);

        if (staff.mfaSecretEnc() == null) {
            throw mfaFailed();
        }
        guard.enforceLockout(staffId);
        byte[] secret = cipher.tryDecrypt(staff.mfaSecretEnc(), mfaAad(staff)).orElseThrow(this::mfaFailed);
        if (!guard.verifyTotp(staffId, secret, code)) {
            log.warn("step-up denied for staff {} — TOTP mismatch", staffId);
            guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "step_up", false);
            throw mfaFailed();
        }

        Instant now = Instant.now(clock);
        Instant exp = now.plusSeconds(300);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(staffId.toString())
                .claim("purpose", "step_up")
                // PLT-007: bound to the minting session and tenant; StepUpVerifier compares them with the caller's token
                .claim("sid", callerJwt.getClaimAsString("sid"))
                .claim("tenantId", callerJwt.getClaimAsString("tenantId"));
        TokenType.STEP_UP.stamp(claimsB, Audience.STEP_UP);
        JwtClaimsSet claims = claimsB.build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        log.info("staff {} obtained a step-up token", staffId);
        return new StepUpTokenResponse(token, 300);
    }

    // ── MFA enrollment (NB-042) ──────────────────────────────────────────
    // Works from either a normal access token (self-service, already logged in) or the
    // mfa_setup_required token issued above (policy forced this staff member to enroll before
    // getting a real session) — both carry sub+tenantId, which is all enroll/confirm need.

    /** PLT-004: with MFA already on, re-enrolment needs the current TOTP or a step-up token; the active secret is untouched until confirm. */
    @Transactional(noRollbackFor = ApiException.class)
    public MfaEnrollResponse enrollMfa(Jwt callerJwt, String currentCode, String stepUpToken) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::notFound);
        if (staff.mfaEnabled()) {
            if (stepUpToken != null && !stepUpToken.isBlank()) {
                stepUpVerifier.require(stepUpToken, staffId);
            } else {
                guard.enforceLockout(staffId);
                if (currentCode == null || staff.mfaSecretEnc() == null
                        || !guard.verifyTotp(staffId, cipher.tryDecrypt(staff.mfaSecretEnc(), mfaAad(staff)).orElse(null), currentCode)) {
                    if (currentCode != null) {
                        guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "totp", false);
                    }
                    throw new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required",
                            "Re-enrolling an authenticator needs your current code or a step-up token.");
                }
            }
        }

        byte[] secret = totpService.generateSecret();
        repo.setPendingMfaSecret(staffId, cipher.encrypt(secret, mfaAad(staff)));
        log.info("staff {} started MFA enrollment", staffId);
        return new MfaEnrollResponse(totpService.toBase32(secret), totpService.otpauthUri(secret, "Nabd", staff.email()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public MfaConfirmResponse confirmMfa(Jwt callerJwt, MfaConfirmRequest req) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        UUID tenantId = UUID.fromString(callerJwt.getClaimAsString("tenantId"));
        tenantContext.set(tenantId);
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::notFound);

        guard.enforceLockout(staffId);
        if (staff.mfaPendingSecretEnc() == null || !guard.verifyTotp(staffId, cipher.tryDecrypt(staff.mfaPendingSecretEnc(), mfaAad(staff)).orElse(null), req.code())) {
            guard.recordAttempt(tenantId, staffId, staff.email(), "totp", false);
            throw mfaFailed();
        }
        repo.confirmPendingMfa(staffId);
        repo.revokeUnusedRecoveryCodes(staffId);
        List<String> codes = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = OpaqueTokens.generate().substring(0, 10).toUpperCase(java.util.Locale.ROOT);
            repo.insertRecoveryCode(tenantId, staffId, OpaqueTokens.sha256Hex(code));
            codes.add(code);
        }
        log.info("staff {} completed MFA enrollment, {} recovery codes issued", staffId, codes.size());
        return new MfaConfirmResponse(codes);
    }

    /** PLT-004: completes an MFA challenge with a single-use recovery code instead of a TOTP. */
    @Transactional(noRollbackFor = ApiException.class)
    public RecoveryVerifyResponse recoveryVerify(RecoveryVerifyRequest req, String ip, String device, String userAgent) {
        Jwt challenge;
        try {
            challenge = jwtDecoder.decode(req.challengeToken(), TokenType.MFA_PENDING);
        } catch (JwtException e) {
            throw challengeInvalid();
        }
        if (!"mfa_pending".equals(challenge.getClaimAsString("purpose"))) {
            throw challengeInvalid();
        }
        tenantContext.set(UUID.fromString(challenge.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(UUID.fromString(challenge.getSubject())).orElseThrow(this::challengeInvalid);
        guard.enforceLockout(staff.id());
        String hash = OpaqueTokens.sha256Hex(req.recoveryCode().trim().toUpperCase(java.util.Locale.ROOT));
        if (!repo.consumeRecoveryCode(staff.id(), hash)) {
            guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "recovery", false);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "recovery-code-invalid", "Recovery code invalid",
                    "That recovery code is not valid.");
        }
        guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "recovery", true);
        String roleName = repo.findRole(staff.roleId()).map(Role::name).orElse("Staff");
        auditService.record(staff.tenantId(), "staff", staff.id(), staff.name(), roleName, ip,
                "auth.recovery_code_used", "staff", staff.id(), null, null);
        TokenPairResponse t = mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
        return new RecoveryVerifyResponse(t.accessToken(), t.refreshToken(), t.expiresIn(), repo.countLiveRecoveryCodes(staff.id()));
    }

    private ApiException challengeInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "challenge-invalid", "Challenge invalid",
                "The sign-in challenge is invalid or has expired.");
    }

    // ── PIN reset (NB-046) ───────────────────────────────────────────────
    // Same no-enumeration shape as requestOtp: always 202, identical timing/copy whether or not
    // the tenant/mobile resolves, so an attacker can't use this endpoint to confirm an account exists.

    @Transactional(noRollbackFor = ApiException.class)
    public void requestPinReset(PinResetRequestRequest req, String ip) {
        enforceIpRateLimit(ip);
        Tenant tenant = repo.findTenantBySlug(req.tenantSlug())
                .filter(t -> !"offboarded".equals(t.status()) && !"suspended".equals(t.status()))
                .orElse(null);
        if (tenant == null) {
            return;
        }
        tenantContext.set(tenant.id());
        Staff staff = repo.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            return;
        }
        String token = OpaqueTokens.generate();
        Instant expiresAt = Instant.now(clock).plus(15, ChronoUnit.MINUTES);
        repo.setPinResetToken(staff.id(), OpaqueTokens.sha256Hex(token), expiresAt);
        otpOutbox.queue(tenant.id(), staff.id(), "pin_reset", token, expiresAt); // OPS-012: sent by the outbox
        log.info("PIN reset token issued to staff {} (tenant {})", staff.id(), tenant.id());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void confirmPinReset(PinResetConfirmRequest req, String ip) {
        enforceIpRateLimit(ip);
        Tenant tenant = repo.findTenantBySlug(req.tenantSlug())
                .filter(t -> !"offboarded".equals(t.status()) && !"suspended".equals(t.status()))
                .orElseThrow(this::invalidCredentials);
        tenantContext.set(tenant.id());
        Staff staff = repo.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null) {
            throw invalidCredentials();
        }
        boolean reset = repo.consumePinResetToken(staff.id(), OpaqueTokens.sha256Hex(req.token()), pinEncoder.encode(req.newPin()));
        if (!reset) {
            throw invalidCredentials();
        }
        log.info("staff {} completed PIN reset (tenant {})", staff.id(), tenant.id());
    }

    // ── idle-timeout unlock (NB-044) ─────────────────────────────────────
    // Client-side lock only — the underlying session/access token is untouched, so draft state in
    // the page (or already-autosaved backend rows) survives the lock exactly as if nothing happened.

    @Transactional(noRollbackFor = ApiException.class)
    public void unlock(Jwt callerJwt, UnlockRequest req) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::notFound);
        guard.enforceLockout(staffId); // 423 before the PIN is even looked at
        if (staff.pinHash() == null || !pinEncoder.matches(req.pin(), staff.pinHash())) {
            guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "unlock", false);
            throw invalidCredentials();
        }
        guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "unlock", true);
    }

    /** PLT-005: invite accept lands the new staff member signed in, unless their role needs MFA first (then a setup token). */
    @Transactional
    public Object sessionAfterInviteAccept(UUID tenantId, UUID staffId, String ip, String device, String userAgent) {
        tenantContext.set(tenantId);
        Staff staff = repo.findStaffById(staffId)
                .orElseThrow(() -> new IllegalStateException("staff " + staffId + " vanished right after invite accept"));
        if (!staff.mfaEnabled() && mfaRequiredByPolicy(staff)) {
            return issueMfaSetupRequired(staff);
        }
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    /** Called from OwnerService once the owner account is through its own MFA — lands the owner in the clinic. */
    @Transactional
    public TokenPairResponse issueTokensForStaff(UUID tenantId, UUID staffId, String ip, String device, String userAgent) {
        tenantContext.set(tenantId);
        if (tenantInactive(tenantId)) { // PLT-007: workspace select into a suspended/offboarded clinic
            throw tenantInactiveError(HttpStatus.FORBIDDEN);
        }
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

        // PLT-007: checked before reuse detection, since suspension revokes the sessions this would read as reuse
        if (tenantInactive(session.tenantId())) {
            throw tenantInactiveError(HttpStatus.UNAUTHORIZED);
        }
        if ("max_age".equals(session.revokedReason())) {
            throw sessionMaxAge();
        }
        if (session.revokedAt() != null) {
            // token already used once (or already revoked) and is being presented again — compromise signal
            log.warn("refresh-token reuse detected — revoking session family {} (staff {}, tenant {})",
                    session.familyId(), session.staffId(), session.tenantId());
            repo.revokeFamily(session.familyId(), "reuse_detected");
            throw invalidRefresh();
        }
        if (session.expiresAt().isBefore(Instant.now(clock))) {
            throw invalidRefresh();
        }
        // PLT-007: absolute lifetime of the family; unlock never mints, so an idle-lock unlock cannot extend it
        if (!Instant.now(clock).isBefore(repo.familyStartedAt(session.tenantId(), session.id()).plus(absoluteSessionHours, ChronoUnit.HOURS))) {
            repo.revokeFamily(session.familyId(), "max_age");
            throw sessionMaxAge();
        }

        Staff staff = repo.findStaffById(session.staffId()).orElseThrow(this::invalidRefresh);
        if (!"active".equals(staff.status())) {
            throw invalidRefresh();
        }
        // PLT-005: the invariant is checked at every mint; a privileged session without MFA ends here and the next sign-in enrols.
        // Owner shadow rows are exempt: the owner account passes its own MFA before entering a clinic.
        if (!staff.mfaEnabled() && privilegedByPolicy(staff, repo.findRole(staff.roleId()).orElseThrow(this::invalidRefresh))
                && !repo.isOwnerLinked(staff.id())) {
            repo.revokeFamily(session.familyId(), "admin_revoke");
            throw invalidRefresh();
        }

        repo.revokeSession(session.id(), "rotated");
        log.debug("session refreshed for staff {} (family {})", staff.id(), session.familyId());
        return mintTokenPair(staff, session.familyId(), ip, device, userAgent);
    }

    @Transactional
    public void logout(UUID tenantId, UUID sessionId) {
        tenantContext.set(tenantId);
        repo.revokeFamilyOf(sessionId, "logout"); // FEF-009: the whole family, so no sibling of this login survives sign-out
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
        int recent = repo.countAttemptsFromIpSince(ip, Instant.now(clock).minus(1, ChronoUnit.MINUTES));
        if (recent >= props.rateLimitPerIpPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many attempts", "Try again shortly.");
        }
    }

    // A still-locked hit records nothing (a client polling during the lock would otherwise renew it forever);
    // see LoginGuard.enforceLockout, shared by every credential factor.
    private void enforceAccountLockout(Staff staff) {
        guard.enforceLockout(staff.id());
    }

    private MfaChallengeResponse issueMfaChallenge(Staff staff) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(props.mfaChallengeTtlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(staff.id().toString())
                .claim("purpose", "mfa_pending")
                .claim("tenantId", staff.tenantId().toString());
        TokenType.MFA_PENDING.stamp(claimsB, Audience.MFA);
        JwtClaimsSet claims = claimsB.build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new MfaChallengeResponse(token, "totp", props.mfaChallengeTtlMinutes() * 60L);
    }

    private TokenPairResponse mintTokenPair(Staff staff, UUID familyId, String ip, String device, String userAgent) {
        return mintTokenPair(staff, familyId, ip, device, userAgent, null);
    }

    /** PLT-012: bg != null mints the read-only emergency token for that grant (see BreakGlassService). */
    TokenPairResponse mintTokenPair(Staff staff, UUID familyId, String ip, String device, String userAgent, BreakGlassSession bg) {
        Role role = repo.findRole(staff.roleId())
                .orElseThrow(() -> new IllegalStateException("role missing for staff " + staff.id()));
        Tenant tenant = repo.findTenantById(staff.tenantId())
                .orElseThrow(() -> new IllegalStateException("tenant missing for staff " + staff.id()));
        List<String> permissions = new java.util.ArrayList<>(flattenGrants(role.grantsJson()));

        // NB-057: fold in any role currently on loan (e.g. covering a doctor's leave). Auditing
        // an expiry here, not on a scheduler, is what "the expiry is audited" means without a
        // background-job platform — see AuthRepository.expireStaleDelegations.
        for (UUID expiredId : repo.expireStaleDelegations(staff.id())) {
            auditService.record(staff.tenantId(), "system", null, "System", "System", null,
                    "staff.delegation_expired", "role_delegation", expiredId, null, null);
        }
        // PLT-012: the emergency token is base + read scope only; loans are not folded in.
        for (Role delegated : bg != null ? List.<Role>of() : repo.findActiveDelegatedRoles(staff.id())) {
            for (String p : flattenGrants(delegated.grantsJson())) {
                if (!permissions.contains(p)) {
                    permissions.add(p);
                }
            }
        }

        // NB-048: same lazy expire-and-audit shape as delegation above.
        for (UUID expiredId : repo.expireStaleBreakGlass(staff.id())) {
            auditService.record(staff.tenantId(), "system", null, "System", "System", null,
                    "auth.break_glass_expired", "break_glass_grant", expiredId, null, null);
        }
        if (bg != null) {
            for (String p : BreakGlassPolicy.BREAK_GLASS_GRANTS) {
                if (!permissions.contains(p)) {
                    permissions.add(p);
                }
            }
        }

        UUID sessionId = bg != null ? bg.sessionId() : UUID.randomUUID();
        Instant now = Instant.now(clock);
        Instant accessExp = bg != null ? bg.expiresAt() : now.plus(props.accessTokenTtlMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(accessExp)
                .subject(staff.id().toString())
                .claim("tenantId", staff.tenantId().toString())
                .claim("roleId", staff.roleId().toString())
                .claim("sid", sessionId.toString())
                .claim("permissions", permissions)
                // Display-only (ClinicNav's clinic-info card and signed-in-as footer) — never
                // used for authorization, so a stale name until next login is harmless.
                .claim("tenantName", tenant.name())
                .claim("tenantRegion", tenant.region())
                .claim("staffName", staff.name())
                .claim("roleName", role.name())
                // Landing only (lib/session landingPathFor): the owner's home is Owner Insights, but
                // their full grants would otherwise match the nursing rule first. From the staff
                // member's own role, never a delegated one.
                .claim("owner", role.builtIn())
                // PLT-003: PinChangeInterceptor refuses everything but pin change/logout while true
                .claim("pinChange", guard.pinChangeRequired(staff.id()));
        if (bg != null) {
            claimsB.claim("bg", bg.grantId().toString());
        }
        TokenType.ACCESS.stamp(claimsB, Audience.STAFF_API);
        JwtClaimsSet claims = claimsB.build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        String rawRefresh = OpaqueTokens.generate();
        Instant refreshExp = bg != null ? bg.expiresAt() : now.plus(props.refreshTokenTtlDays(), ChronoUnit.DAYS);
        repo.insertSession(sessionId, staff.tenantId(), staff.id(), familyId,
                OpaqueTokens.sha256Hex(rawRefresh), refreshExp, device, ip, userAgent, now);

        return new TokenPairResponse(accessToken, rawRefresh, Duration.between(now, accessExp).toSeconds(),
                Duration.between(now, refreshExp).toSeconds());
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

    /** PLT-044: pending and active secrets share one AAD column, since confirmPendingMfa moves the value in SQL. */
    static FieldCipher.Aad mfaAad(Staff staff) {
        return FieldCipher.Aad.of(staff.tenantId(), "staff", "mfa_secret", staff.id());
    }

    private ApiException mfaFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "mfa-failed", "MFA verification failed",
                "The code is incorrect or the challenge has expired.");
    }

    private boolean tenantInactive(UUID tenantId) {
        return repo.findTenantById(tenantId).map(t -> com.nabd.hms.common.SessionRevocation.INACTIVE_TENANT_STATUSES.contains(t.status()))
                .orElse(true);
    }

    private ApiException tenantInactiveError(HttpStatus status) {
        return new ApiException(status, "TENANT_INACTIVE", "Clinic not active",
                "This clinic is suspended or closed; contact the clinic owner.");
    }

    private ApiException sessionMaxAge() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_MAX_AGE", "Session too old",
                "Sessions last one shift; sign in again.");
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
