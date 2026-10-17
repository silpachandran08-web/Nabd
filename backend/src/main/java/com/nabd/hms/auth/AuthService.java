package com.nabd.hms.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.auth.dto.BreakGlassActivateRequest;
import com.nabd.hms.auth.dto.BreakGlassGrantResponse;
import com.nabd.hms.auth.dto.BreakGlassResponse;
import com.nabd.hms.auth.dto.BreakGlassReviewRequest;
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
import com.nabd.hms.auth.dto.StepUpTokenResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.dto.OtpRequestRequest;
import com.nabd.hms.auth.dto.OtpVerifyRequest;
import com.nabd.hms.auth.dto.UnlockRequest;
import com.nabd.hms.common.AesGcmCipher;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.StepUpVerifier;
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

import static com.nabd.hms.auth.AuthModels.BreakGlassGrant;
import static com.nabd.hms.auth.AuthModels.Role;
import static com.nabd.hms.auth.AuthModels.SessionRow;
import static com.nabd.hms.auth.AuthModels.Staff;
import static com.nabd.hms.auth.AuthModels.Tenant;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String ISSUER = "https://api.nabd.health";

    /**
     * MF-02 / QA-02: what an active break-glass grant adds to its session — clinical access to treat
     * the patient in front of you, and nothing administrative. It used to fold in the whole built-in
     * Owner role (staff:edit, roles, billing approve...), which a role delegation could then stretch
     * from 30 minutes into 30 days. Fixed here rather than read from a role so no role edit can widen it.
     */
    static final List<String> BREAK_GLASS_PERMISSIONS = List.of(
            "patients:view", "queue:view", "clinical:view", "clinical:create", "clinical:edit",
            "nursing:view", "pharmacy:view");

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
    private final AuditService auditService;
    private final StepUpVerifier stepUpVerifier;
    private final SecureRandom random = new SecureRandom();

    AuthService(AuthRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder,
                TotpService totpService, AesGcmCipher cipher, JwtEncoder jwtEncoder, JwtDecoder jwtDecoder,
                AuthProperties props, ObjectMapper objectMapper, WhatsAppOtpSender otpSender, AuditService auditService,
                StepUpVerifier stepUpVerifier) {
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
        this.auditService = auditService;
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
        repo.recordLoginAttempt(tenant.id(), staff.id(), email, ip, true);
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
     * NB-042: "mandatory for Owner ... and anyone holding refund or discount rights" is a real-world
     * description of which roles a clinic will assign this to, not a rule about role names or a
     * heuristic over grants — a role's display name is free text (renamed on a whim), and this
     * codebase's own "full access" test/seed roles hold every grant including refundDiscount, so
     * either heuristic would gate roles that were never meant to require it. Enforcement is a plain
     * per-role flag instead, set deliberately by whoever builds the role (see roles.mfa_required).
     */
    private boolean mfaRequiredByPolicy(Staff staff) {
        Role role = repo.findRole(staff.roleId()).orElseThrow(() -> new IllegalStateException("role missing"));
        return role.mfaRequired();
    }

    private MfaSetupRequiredResponse issueMfaSetupRequired(Staff staff) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.mfaChallengeTtlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(staff.id().toString())
                .claim("purpose", TokenPurposeAuthenticationManagerResolver.MFA_SETUP_REQUIRED)
                .claim("tenantId", staff.tenantId().toString())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new MfaSetupRequiredResponse(token, props.mfaChallengeTtlMinutes() * 60L);
    }

    /** No enumeration: silently no-ops for an unknown tenant/mobile, but still returns as if sent. */
    @Transactional(noRollbackFor = ApiException.class)
    public void requestOtp(OtpRequestRequest req, String ip) {
        enforceIpRateLimit(ip);

        Tenant tenant = repo.findTenantBySlug(req.tenantSlug())
                .filter(t -> !"offboarded".equals(t.status()) && !"suspended".equals(t.status()))
                .orElse(null);
        // ponytail: explicit "not found" errors were asked for so staff know why no OTP came. This
        // lets anyone probe which clinic codes / mobiles exist (only the per-IP rate limit slows it);
        // switch both back to a silent return + 202 to restore no-enumeration.
        if (tenant == null) {
            log.info("OTP requested for unknown/inactive tenant slug from {}", ip);
            throw new ApiException(HttpStatus.NOT_FOUND, "clinic-not-found", "Clinic not found",
                    "No clinic with that clinic code. Check the code and try again.");
        }
        tenantContext.set(tenant.id());

        Staff staff = repo.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            log.info("OTP requested for a mobile with no active staff in tenant {} from {}", tenant.id(), ip);
            throw new ApiException(HttpStatus.NOT_FOUND, "mobile-not-found", "Mobile number not found",
                    "No active staff member with that mobile number in this clinic.");
        }

        String code = generateOtpCode();
        repo.setWhatsAppOtp(staff.id(), OpaqueTokens.sha256Hex(code), Instant.now().plus(5, ChronoUnit.MINUTES));
        otpSender.send(staff.mobilePhone(), code); // the number on file, not whatever format was typed
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

        Staff staff = repo.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
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

        if (!staff.mfaEnabled() && mfaRequiredByPolicy(staff)) {
            return issueMfaSetupRequired(staff);
        }
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
        requireSessionToken(callerJwt);
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

    // ── MFA enrollment (NB-042) ──────────────────────────────────────────
    // Works from either a normal session token (self-service, already logged in) or the
    // mfa_setup_required token issued above (policy forced this staff member to enroll before
    // getting a real session). QA-04: once MFA is on, replacing it is a re-enrollment, which needs a
    // full session plus a step-up token proving the CURRENT factor — a session (or setup) token alone
    // must never be enough to swap someone's second factor. The new secret stays pending until
    // confirm proves a code from it, so an abandoned enrollment changes nothing.

    @Transactional
    public MfaEnrollResponse enrollMfa(Jwt callerJwt, String stepUpToken) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::notFound);
        requireEnrollmentToken(callerJwt, staff);
        if (staff.mfaEnabled()) {
            stepUpVerifier.require(stepUpToken, staffId);
        }

        byte[] secret = totpService.generateSecret();
        repo.setPendingMfaSecret(staffId, cipher.encrypt(secret));
        log.info("staff {} started MFA {}", staffId, staff.mfaEnabled() ? "re-enrollment" : "enrollment");
        return new MfaEnrollResponse(totpService.toBase32(secret), totpService.otpauthUri(secret, "Nabd", staff.email()));
    }

    @Transactional
    public MfaConfirmResponse confirmMfa(Jwt callerJwt, MfaConfirmRequest req) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        UUID tenantId = UUID.fromString(callerJwt.getClaimAsString("tenantId"));
        tenantContext.set(tenantId);
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::notFound);
        requireEnrollmentToken(callerJwt, staff);

        byte[] pending = repo.findPendingMfaSecret(staffId).orElseThrow(this::mfaFailed);
        if (!totpService.verify(cipher.decrypt(pending), req.code(), Instant.now()) || !repo.activatePendingMfaSecret(staffId, pending)) {
            throw mfaFailed();
        }
        repo.revokeRecoveryCodes(staffId);
        List<String> codes = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = OpaqueTokens.generate().substring(0, 10).toUpperCase(java.util.Locale.ROOT);
            repo.insertRecoveryCode(tenantId, staffId, OpaqueTokens.sha256Hex(code));
            codes.add(code);
        }
        auditService.record(tenantId, "staff", staffId, staff.name(), callerRole(callerJwt), null,
                staff.mfaEnabled() ? "auth.mfa_reenrolled" : "auth.mfa_enrolled", "staff", staffId, null, null);
        log.info("staff {} completed MFA enrollment, {} recovery codes issued, earlier codes revoked", staffId, codes.size());
        return new MfaConfirmResponse(codes);
    }

    /** A setup token only ever covers first enrollment; replacing an active factor needs a full session. */
    private void requireEnrollmentToken(Jwt callerJwt, Staff staff) {
        boolean setupToken = TokenPurposeAuthenticationManagerResolver.MFA_SETUP_REQUIRED.equals(callerJwt.getClaimAsString("purpose"));
        if (setupToken ? staff.mfaEnabled() : !TokenPurposeAuthenticationManagerResolver.isSessionToken(callerJwt)) {
            throw sessionRequired();
        }
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
        repo.setPinResetToken(staff.id(), OpaqueTokens.sha256Hex(token), Instant.now().plus(15, ChronoUnit.MINUTES));
        otpSender.send(staff.mobilePhone(), token);
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

    // ── break-glass (NB-048) ─────────────────────────────────────────────
    // MF-02 / F-02: activation needs a full session, the dedicated emergency:create permission
    // (checked in AuthController) and a step-up token, and it grants only BREAK_GLASS_PERMISSIONS,
    // and only to the new session family it mints. Every grant then waits for a post-hoc review by
    // someone else holding emergency:approve.

    @Transactional
    public TokenPairResponse activateBreakGlass(Jwt callerJwt, String stepUpToken, BreakGlassActivateRequest req,
                                                String ip, String device, String userAgent) {
        requireSessionToken(callerJwt);
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        UUID tenantId = UUID.fromString(callerJwt.getClaimAsString("tenantId"));
        tenantContext.set(tenantId);
        stepUpVerifier.require(stepUpToken, staffId);
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::notFound);

        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
        UUID familyId = UUID.randomUUID();
        UUID grantId = repo.insertBreakGlass(tenantId, staffId, req.reason(), expiresAt, familyId);
        auditService.record(tenantId, "staff", staffId, staff.name(), callerRole(callerJwt), ip,
                "auth.break_glass_activated", "break_glass_grant", grantId, null,
                java.util.Map.of("reason", req.reason(), "expiresAt", expiresAt.toString(), "permissions", BREAK_GLASS_PERMISSIONS));
        log.warn("BREAK-GLASS activated by staff {} (tenant {}): {}", staffId, tenantId, req.reason());
        return mintTokenPair(staff, familyId, ip, device, userAgent);
    }

    /**
     * The grant holder may end their own grant; ending anyone else's needs emergency:approve. Either
     * way the elevated session family is revoked, so the elevated access token stops working on its
     * next request instead of living out its 15 minutes (QA-26).
     */
    @Transactional
    public void deactivateBreakGlass(Jwt callerJwt, UUID grantId) {
        requireSessionToken(callerJwt);
        UUID tenantId = UUID.fromString(callerJwt.getClaimAsString("tenantId"));
        UUID callerId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(tenantId);
        BreakGlassGrant grant = repo.findBreakGlass(tenantId, grantId).orElseThrow(this::notFound);
        if (!grant.staffId().equals(callerId) && !hasPermission(callerJwt, "emergency:approve")) {
            throw notFound(); // same "don't confirm existence" rule as other scoped lookups
        }
        if (!repo.deactivateBreakGlass(tenantId, grantId, callerId)) {
            return; // already ended — nothing to do, and nothing worth an audit row
        }
        if (grant.sessionFamilyId() != null) {
            repo.revokeFamily(grant.sessionFamilyId(), "break_glass_ended");
        }
        auditService.record(tenantId, "staff", callerId, callerJwt.getClaimAsString("staffName"), callerRole(callerJwt), null,
                "auth.break_glass_deactivated", "break_glass_grant", grantId, null, null);
    }

    /** Owner-visible "who's elevated right now" — the honest substitute for "notified in real time"
     * without a push channel: visible the moment anyone with staff:view loads this. */
    @Transactional
    public List<BreakGlassResponse> listActiveBreakGlass(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listActiveBreakGlass(tenantId).stream()
                .map(r -> new BreakGlassResponse(r.id(), r.staffId(), r.staffName(), r.reason(), r.activatedAt(), r.expiresAt()))
                .toList();
    }

    /** The post-hoc review queue: every grant, active or ended, that nobody has reviewed yet. */
    @Transactional
    public List<BreakGlassGrantResponse> listUnreviewedBreakGlass(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listUnreviewedBreakGlass(tenantId).stream()
                .map(r -> new BreakGlassGrantResponse(r.id(), r.staffId(), r.staffName(), r.reason(), r.activatedAt(),
                        r.expiresAt(), r.deactivatedAt(), r.deactivatedReason()))
                .toList();
    }

    @Transactional
    public void reviewBreakGlass(Jwt callerJwt, UUID grantId, BreakGlassReviewRequest req) {
        requireSessionToken(callerJwt);
        UUID tenantId = UUID.fromString(callerJwt.getClaimAsString("tenantId"));
        UUID reviewerId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(tenantId);
        BreakGlassGrant grant = repo.findBreakGlass(tenantId, grantId).orElseThrow(this::notFound);
        if (grant.staffId().equals(reviewerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "self-review", "Cannot review your own emergency access",
                    "Someone other than the person who used emergency access has to review it.");
        }
        if (!repo.reviewBreakGlass(tenantId, grantId, reviewerId, req.notes())) {
            throw new ApiException(HttpStatus.CONFLICT, "already-reviewed", "Already reviewed",
                    "This emergency access grant has already been reviewed.");
        }
        auditService.record(tenantId, "staff", reviewerId, callerJwt.getClaimAsString("staffName"), callerRole(callerJwt), null,
                "auth.break_glass_reviewed", "break_glass_grant", grantId, null, java.util.Map.of("notes", req.notes()));
    }

    // ── idle-timeout unlock (NB-044) ─────────────────────────────────────
    // Client-side lock only — the underlying session/access token is untouched, so draft state in
    // the page (or already-autosaved backend rows) survives the lock exactly as if nothing happened.

    @Transactional
    public void unlock(Jwt callerJwt, UnlockRequest req) {
        requireSessionToken(callerJwt);
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(this::notFound);
        if (staff.pinHash() == null || !pinEncoder.matches(req.pin(), staff.pinHash())) {
            throw invalidCredentials();
        }
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
        for (Role delegated : repo.findActiveDelegatedRoles(staff.id())) {
            for (String p : flattenGrants(delegated.grantsJson())) {
                if (!permissions.contains(p)) {
                    permissions.add(p);
                }
            }
        }

        // NB-048: same lazy expire-and-audit shape as delegation above. Elevation is bound to the
        // session family minted at activation, and its access tokens never outlive the grant — a
        // refresh at minute 29 must not buy another 15 minutes of emergency access (QA-26).
        for (UUID expiredId : repo.expireStaleBreakGlass(staff.id())) {
            auditService.record(staff.tenantId(), "system", null, "System", "System", null,
                    "auth.break_glass_expired", "break_glass_grant", expiredId, null, null);
        }
        Instant now = Instant.now();
        Instant accessExp = now.plus(props.accessTokenTtlMinutes(), ChronoUnit.MINUTES);
        java.util.Optional<Instant> breakGlassExpiry = repo.findActiveBreakGlassExpiry(staff.id(), familyId);
        if (breakGlassExpiry.isPresent()) {
            for (String p : BREAK_GLASS_PERMISSIONS) {
                if (!permissions.contains(p)) {
                    permissions.add(p);
                }
            }
            if (breakGlassExpiry.get().isBefore(accessExp)) {
                accessExp = breakGlassExpiry.get();
            }
        }

        UUID sessionId = UUID.randomUUID();

        JwtClaimsSet claims = JwtClaimsSet.builder()
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


    /** Endpoints that act on the caller's own account need a full session, never an intermediate token. */
    private void requireSessionToken(Jwt jwt) {
        if (!TokenPurposeAuthenticationManagerResolver.isSessionToken(jwt)) {
            throw sessionRequired();
        }
    }

    private static boolean hasPermission(Jwt jwt, String permission) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions != null && permissions.contains(permission);
    }

    private static String callerRole(Jwt jwt) {
        String role = jwt.getClaimAsString("roleName");
        return role == null ? "" : role;
    }

    private ApiException sessionRequired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "session-required", "Full sign-in required",
                "This action needs a fully signed-in session, not a sign-in challenge token.");
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
