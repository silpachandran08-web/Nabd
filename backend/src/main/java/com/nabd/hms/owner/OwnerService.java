package com.nabd.hms.owner;

import com.nabd.hms.auth.AuthService;
import com.nabd.hms.auth.MfaPolicy;
import com.nabd.hms.auth.TotpService;
import com.nabd.hms.auth.dto.MfaChallengeResponse;
import com.nabd.hms.auth.dto.MfaEnrollResponse;
import com.nabd.hms.auth.dto.MfaSetupRequiredResponse;
import com.nabd.hms.auth.dto.RecoveryVerifyRequest;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.owner.dto.OwnerMfaConfirmRequest;
import com.nabd.hms.owner.dto.OwnerMfaConfirmResponse;
import com.nabd.hms.owner.dto.OwnerMfaEnrollRequest;
import com.nabd.hms.owner.dto.OwnerMfaVerifyRequest;
import com.nabd.hms.owner.dto.OwnerWorkspaceTokenResponse;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.config.AuthProperties;
import com.nabd.hms.owner.dto.BrandWorkspaceResponse;
import com.nabd.hms.owner.dto.ClinicSummaryResponse;
import com.nabd.hms.owner.dto.OwnerLoginRequest;
import com.nabd.hms.owner.dto.PendingWorkspaceTokenResponse;
import com.nabd.hms.owner.dto.WorkspaceSelectRequest;
import com.nabd.hms.owner.dto.WorkspacesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenPopulation;
import com.nabd.hms.shared.security.TokenType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.owner.OwnerModels.BrandWorkspace;
import static com.nabd.hms.owner.OwnerModels.Owner;

/**
 * Owner is a top-level account, not a row in any clinic's staff table (see V6 migration and the
 * design discussion this implements). Login is a two-step handoff: PIN authenticates the Owner and
 * issues a short-lived "pending workspace" token; selecting a clinic then mints a completely normal
 * staff-shaped access/refresh token pair via AuthService.issueTokensForStaff, reusing every existing
 * controller's permission checks unchanged. Brand-wide (multi-clinic) workspace selection is
 * deliberately not implemented yet — no dashboard endpoint consumes it, and the RLS support for it
 * (app.accessible_tenant_ids) already shipped in V6 waiting for that later phase.
 */
@Service
public class OwnerService {

    private static final Logger log = LoggerFactory.getLogger(OwnerService.class);
    private static final String ISSUER = TokenPopulation.OWNER.issuer(); // PLT-009: own issuer and key set
    private static final String PENDING_WORKSPACE_PURPOSE = "owner_pending_workspace";
    private static final String OWNER_MFA_PENDING = "owner_mfa_pending";
    private static final String OWNER_MFA_SETUP = "owner_mfa_setup";

    private final OwnerRepository repo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties props;
    private final AuthService authService;
    private final MfaPolicy mfaPolicy;
    private final TotpService totp;
    private final FieldCipher cipher;
    private final ChallengeTokenDecoder challengeDecoder;
    private final AuditService auditService;

    private final Clock clock;

    OwnerService(OwnerRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder,
                 JwtEncoder jwtEncoder, AuthProperties props, AuthService authService, MfaPolicy mfaPolicy,
                 TotpService totp, FieldCipher cipher, ChallengeTokenDecoder challengeDecoder, AuditService auditService,
                 Clock clock) {
        this.clock = clock;
        this.mfaPolicy = mfaPolicy;
        this.totp = totp;
        this.cipher = cipher;
        this.challengeDecoder = challengeDecoder;
        this.auditService = auditService;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.authService = authService;
    }

    // noRollbackFor: recordLoginAttempt() must survive the ApiException thrown right after it, same
    // reasoning as AuthService.login() — see that class for the rollback gotcha this avoids.
    @Transactional(noRollbackFor = ApiException.class)
    public Object login(OwnerLoginRequest req, String ip) {
        enforceIpRateLimit(ip);
        String email = req.email().toLowerCase();

        Owner owner = repo.findByEmail(email).orElse(null);
        if (owner == null || !"active".equals(owner.status())) {
            repo.recordLoginAttempt(owner == null ? null : owner.id(), email, ip, false);
            throw invalidCredentials();
        }

        enforceAccountLockout(owner);

        if (owner.pinHash() == null || !pinEncoder.matches(req.pin(), owner.pinHash())) {
            repo.recordLoginAttempt(owner.id(), email, ip, false);
            throw invalidCredentials();
        }
        if (!repo.mfaState(owner.id()).enabled()) { // with MFA the success is recorded after the second factor, so a PIN cannot reset TOTP failures
            repo.recordLoginAttempt(owner.id(), email, ip, true);
        }
        log.info("owner {} authenticated via PIN", owner.id());
        return afterFirstFactor(owner);
    }

    /** PLT-005: enrolled owners get a TOTP challenge, unenrolled ones a setup token; the workspace token comes only after. */
    private Object afterFirstFactor(Owner owner) {
        if (repo.mfaState(owner.id()).enabled()) {
            return new MfaChallengeResponse(ownerChallengeToken(owner, TokenType.MFA_PENDING, OWNER_MFA_PENDING), "totp",
                    props.mfaChallengeTtlMinutes() * 60L);
        }
        if (mfaPolicy.requiredForOwner()) {
            return new MfaSetupRequiredResponse(ownerChallengeToken(owner, TokenType.MFA_SETUP, OWNER_MFA_SETUP),
                    props.mfaChallengeTtlMinutes() * 60L);
        }
        return new PendingWorkspaceTokenResponse(issuePendingWorkspaceToken(owner), 300);
    }

    @Transactional(noRollbackFor = ApiException.class) // the failed attempt must survive the 401
    public OwnerWorkspaceTokenResponse verifyMfa(OwnerMfaVerifyRequest req, String ip) {
        Owner owner = ownerFrom(req.challengeToken(), TokenType.MFA_PENDING, OWNER_MFA_PENDING);
        enforceAccountLockout(owner);
        OwnerRepository.MfaState mfa = repo.mfaState(owner.id());
        // a blob bound to another row fails closed as an MFA failure (401), like staff/operator MFA
        long step = mfa.secretEnc() == null ? -1 : cipher.tryDecrypt(mfa.secretEnc(), mfaAad(owner)).map(k -> totp.matchStep(k, req.code(), Instant.now(clock))).orElse(-1L);
        if (step < 0 || !repo.claimTotpStep(owner.id(), step)) {
            repo.recordLoginAttempt(owner.id(), owner.email(), ip, false);
            log.warn("MFA verification failed for owner {}", owner.id());
            throw mfaFailed();
        }
        repo.recordLoginAttempt(owner.id(), owner.email(), ip, true);
        return new OwnerWorkspaceTokenResponse(issuePendingWorkspaceToken(owner), 300, null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OwnerWorkspaceTokenResponse recoveryVerify(RecoveryVerifyRequest req, String ip) {
        Owner owner = ownerFrom(req.challengeToken(), TokenType.MFA_PENDING, OWNER_MFA_PENDING);
        enforceAccountLockout(owner);
        Integer left = repo.consumeRecoveryCode(owner.id(), OpaqueTokens.sha256Hex(req.recoveryCode().trim().toUpperCase(Locale.ROOT)));
        if (left == null) {
            repo.recordLoginAttempt(owner.id(), owner.email(), ip, false);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "recovery-code-invalid", "Recovery code invalid",
                    "That recovery code is not valid.");
        }
        repo.recordLoginAttempt(owner.id(), owner.email(), ip, true);
        audit(owner, ip, "owner_auth.recovery_code_used");
        return new OwnerWorkspaceTokenResponse(issuePendingWorkspaceToken(owner), 300, left);
    }

    @Transactional
    public MfaEnrollResponse enrollMfa(OwnerMfaEnrollRequest req) {
        Owner owner = ownerFrom(req.setupToken(), TokenType.MFA_SETUP, OWNER_MFA_SETUP);
        byte[] secret = totp.generateSecret();
        repo.setPendingMfaSecret(owner.id(), cipher.encrypt(secret, mfaAad(owner)));
        log.info("owner {} started MFA enrollment", owner.id());
        return new MfaEnrollResponse(totp.toBase32(secret), totp.otpauthUri(secret, "Nabd", owner.email()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OwnerMfaConfirmResponse confirmMfa(OwnerMfaConfirmRequest req, String ip) {
        Owner owner = ownerFrom(req.setupToken(), TokenType.MFA_SETUP, OWNER_MFA_SETUP);
        enforceAccountLockout(owner);
        byte[] pending = repo.mfaState(owner.id()).pendingSecretEnc();
        long step = pending == null ? -1 : cipher.tryDecrypt(pending, mfaAad(owner)).map(k -> totp.matchStep(k, req.code(), Instant.now(clock))).orElse(-1L);
        if (step < 0 || !repo.claimTotpStep(owner.id(), step)) {
            repo.recordLoginAttempt(owner.id(), owner.email(), ip, false);
            throw mfaFailed();
        }
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 8; i++) { // same shape as staff recovery codes (AuthService.confirmMfa)
            String code = OpaqueTokens.generate().substring(0, 10).toUpperCase(Locale.ROOT);
            codes.add(code);
            hashes.add(OpaqueTokens.sha256Hex(code));
        }
        if (!repo.confirmPendingMfa(owner.id(), hashes)) {
            throw mfaFailed();
        }
        repo.recordLoginAttempt(owner.id(), owner.email(), ip, true);
        audit(owner, ip, "owner_auth.mfa_enrolled");
        return new OwnerMfaConfirmResponse(codes, issuePendingWorkspaceToken(owner), 300);
    }

    private Owner ownerFrom(String token, TokenType type, String purpose) {
        try {
            Jwt jwt = challengeDecoder.decode(token, type, TokenPopulation.OWNER);
            if (purpose.equals(jwt.getClaimAsString("purpose"))) {
                Owner owner = repo.findById(UUID.fromString(jwt.getSubject())).orElse(null);
                if (owner != null && "active".equals(owner.status())) {
                    return owner;
                }
            }
        } catch (JwtException | IllegalArgumentException e) {
            // fall through: expired, tampered, wrong type or audience all get the same 401
        }
        throw type == TokenType.MFA_SETUP
                ? new ApiException(HttpStatus.UNAUTHORIZED, "token-type-not-allowed", "Token type not allowed",
                        "This action needs the MFA setup token from owner sign-in.")
                : mfaFailed();
    }

    private String ownerChallengeToken(Owner owner, TokenType type, String purpose) {
        Instant now = Instant.now(clock);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(props.mfaChallengeTtlMinutes(), ChronoUnit.MINUTES))
                .subject(owner.id().toString())
                .claim("purpose", purpose); // no tenantId: staff /auth/mfa/* refuse it (purpose and the missing claim)
        return jwtEncoder.encode(JwtEncoderParameters.from(type.stamp(claimsB, Audience.MFA).build())).getTokenValue();
    }

    // owners are not an audit actor type (V14/V126 CHECK); the platform ledger records it as a system event on the owner entity
    private void audit(Owner owner, String ip, String action) {
        auditService.recordPlatform("system", null, "owner account", "owner", ip, action, "owner", owner.id(), null, null);
    }

    private ApiException mfaFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "mfa-failed", "MFA verification failed",
                "The code is incorrect or the challenge has expired.");
    }

    /** Called from provisioning (verify_invite_owner) on every job for this owner, not just their
     * first — empty once they've activated, so a second/third clinic for the same owner correctly
     * skips re-inviting. Same "raw token relayed to the caller, only its hash persisted" shape as
     * StaffService.invite(), 72h expiry to match. Re-invitable: an owner who never accepted their
     * first invite (e.g. their second clinic gets provisioned before they used it) just overwrites
     * the stale token with a fresh one, rather than being stuck with an expired link forever. */
    @Transactional
    public Optional<String> invite(UUID ownerId) {
        Owner owner = repo.findById(ownerId).orElseThrow(this::notFound);
        if (owner.pinHash() != null) {
            return Optional.empty();
        }
        String rawToken = OpaqueTokens.generate();
        repo.setInviteToken(ownerId, OpaqueTokens.sha256Hex(rawToken), Instant.now(clock).plus(72, ChronoUnit.HOURS));
        log.info("owner {} invited to set their account PIN", ownerId);
        return Optional.of(rawToken);
    }

    /** Mirrors StaffService.acceptInvite()'s "setting the PIN activates the account, and logs you
     * straight in" convenience — translated to the owner's two-step login: hands back a pending
     * workspace token instead of real tokens, since which clinic to enter still isn't chosen yet. */
    @Transactional
    public Object acceptInvite(String rawToken, String pin) {
        Owner owner = repo.findByInviteTokenHash(OpaqueTokens.sha256Hex(rawToken)).orElseThrow(this::inviteInvalid);
        repo.acceptInvite(owner.id(), pinEncoder.encode(pin));
        log.info("owner {} accepted invite and activated", owner.id());
        return afterFirstFactor(owner); // PLT-005: straight into enrolment when MFA is enforced
    }

    @Transactional
    public WorkspacesResponse listWorkspaces(Jwt pendingToken) {
        UUID ownerId = requirePendingWorkspaceToken(pendingToken);
        var brands = repo.listWorkspaces(ownerId).stream().map(this::toResponse).toList();
        return new WorkspacesResponse(brands);
    }

    @Transactional
    public TokenPairResponse selectWorkspace(Jwt pendingToken, WorkspaceSelectRequest req,
                                              String ip, String device, String userAgent) {
        UUID ownerId = requirePendingWorkspaceToken(pendingToken);
        Owner owner = repo.findById(ownerId).orElseThrow(this::notFound);

        // Data-scoped authorization, not a permission check — an owner selecting a clinic they don't
        // own gets 404, not 403, same "don't confirm existence" rule as every other scope gate here.
        if (!repo.ownsClinic(ownerId, req.clinicId())) {
            throw notFound();
        }

        tenantContext.set(req.clinicId());
        UUID shadowStaffId = repo.findOrCreateShadowStaff(ownerId, req.clinicId(), owner.name());
        log.info("owner {} entered clinic workspace {} (staff {})", ownerId, req.clinicId(), shadowStaffId);

        return authService.issueTokensForStaff(req.clinicId(), shadowStaffId, ip, device, userAgent);
    }

    private UUID requirePendingWorkspaceToken(Jwt jwt) {
        if (jwt == null || !PENDING_WORKSPACE_PURPOSE.equals(jwt.getClaimAsString("purpose"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid-workspace-token", "Invalid workspace token",
                    "This action requires a valid pending-workspace token from /v1/owners/auth/login.");
        }
        return UUID.fromString(jwt.getSubject());
    }

    private String issuePendingWorkspaceToken(Owner owner) {
        Instant now = Instant.now(clock);
        Instant exp = now.plusSeconds(300);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(owner.id().toString())
                .claim("purpose", PENDING_WORKSPACE_PURPOSE);
        JwtClaimsSet claims = TokenType.OWNER_SELECT.stamp(claimsB, Audience.OWNER_SELECT).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private void enforceIpRateLimit(String ip) {
        int recent = repo.countAttemptsFromIpSince(ip, Instant.now(clock).minus(1, ChronoUnit.MINUTES));
        if (recent >= props.rateLimitPerIpPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many attempts", "Try again shortly.");
        }
    }

    // Mirrors AuthService.enforceAccountLockout exactly, including the fix for the self-renewing
    // lockout bug found this session: a still-locked hit does NOT record a new failed attempt, or
    // the lock would renew forever under repeated requests. See AuthService for the full incident.
    private void enforceAccountLockout(Owner owner) {
        int failures = repo.countFailedAttemptsSinceLastSuccess(owner.id());
        if (failures < props.lockoutThreshold()) {
            return;
        }
        Instant lastFailedAt = repo.lastFailedAttemptAt(owner.id()).orElse(Instant.EPOCH);
        Instant lockedUntil = lastFailedAt.plus(backoffDuration(failures));
        if (Instant.now(clock).isBefore(lockedUntil)) {
            log.warn("account locked: owner {} has {} failed attempts, locked until {}", owner.id(), failures, lockedUntil);
            throw accountLocked();
        }
    }

    private Duration backoffDuration(int failureCount) {
        int overBy = failureCount - props.lockoutThreshold() + 1;
        long minutes = Math.min(30, 1L << (overBy - 1));
        return Duration.ofMinutes(minutes);
    }

    private BrandWorkspaceResponse toResponse(BrandWorkspace b) {
        var clinics = b.clinics().stream()
                .map(c -> new ClinicSummaryResponse(c.id(), c.name(), c.slug(), c.region(), c.status()))
                .toList();
        return new BrandWorkspaceResponse(b.id(), b.name(), b.status(), clinics);
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
                "Email or PIN is incorrect.");
    }

    private ApiException accountLocked() {
        return new ApiException(HttpStatus.LOCKED, "account-locked", "Account locked",
                "Too many failed attempts. Try again later.");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }

    private ApiException inviteInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invite-invalid", "Invite invalid",
                "This invite link is invalid or has expired.");
    }

    /** PLT-044: AAD binds the owner's TOTP secret to its row; pending and active share it (SQL moves pending -> active). */
    private static FieldCipher.Aad mfaAad(Owner owner) {
        return FieldCipher.Aad.of("platform", "owners", "mfa_secret", owner.id());
    }
}
