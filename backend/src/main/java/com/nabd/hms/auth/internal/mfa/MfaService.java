package com.nabd.hms.auth.internal.mfa;

import com.nabd.hms.auth.AuthModels.Role;
import com.nabd.hms.auth.AuthModels.Staff;
import com.nabd.hms.auth.AuthRepository;
import com.nabd.hms.auth.LoginGuard;
import com.nabd.hms.auth.TotpService;
import com.nabd.hms.auth.dto.MfaConfirmRequest;
import com.nabd.hms.auth.dto.MfaConfirmResponse;
import com.nabd.hms.auth.dto.MfaEnrollResponse;
import com.nabd.hms.auth.dto.MfaVerifyRequest;
import com.nabd.hms.auth.dto.RecoveryVerifyRequest;
import com.nabd.hms.auth.dto.RecoveryVerifyResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.internal.token.AuthErrors;
import com.nabd.hms.auth.internal.token.TokenService;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import com.nabd.hms.shared.security.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** PLT-034 (split from AuthService): the TOTP second factor, NB-042 enrolment and PLT-004 recovery codes. */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final AuthRepository repo;
    private final TenantContext tenantContext;
    private final TotpService totpService;
    private final FieldCipher cipher;
    private final ChallengeTokenDecoder jwtDecoder;
    private final AuditService auditService;
    private final LoginGuard guard;
    private final StepUpVerifier stepUpVerifier;
    private final TokenService tokens;

    MfaService(AuthRepository repo, TenantContext tenantContext, TotpService totpService, FieldCipher cipher,
               ChallengeTokenDecoder jwtDecoder, AuditService auditService, LoginGuard guard, StepUpVerifier stepUpVerifier,
               TokenService tokens) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.totpService = totpService;
        this.cipher = cipher;
        this.jwtDecoder = jwtDecoder;
        this.auditService = auditService;
        this.guard = guard;
        this.stepUpVerifier = stepUpVerifier;
        this.tokens = tokens;
    }

    @Transactional(noRollbackFor = ApiException.class) // the failed-attempt row must survive the 401
    public TokenPairResponse verifyMfa(MfaVerifyRequest req, String ip, String device, String userAgent) {
        Jwt challenge;
        try {
            challenge = jwtDecoder.decode(req.challengeId(), TokenType.MFA_PENDING);
        } catch (JwtException e) {
            throw AuthErrors.mfaFailed(); // covers expired (5 min TTL) and tampered tokens alike
        }
        if (!"mfa_pending".equals(challenge.getClaimAsString("purpose"))) {
            throw AuthErrors.mfaFailed();
        }

        // tenantId travels in the challenge claims (known at issue time) so this lookup can set
        // RLS context *before* touching the tenant-scoped staff table — findStaffById alone can't
        // resolve a tenant to scope by, since staff is behind the same RLS it would need to bypass.
        tenantContext.set(UUID.fromString(challenge.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(UUID.fromString(challenge.getSubject())).orElseThrow(AuthErrors::mfaFailed);

        if (staff.mfaSecretEnc() == null) {
            throw AuthErrors.mfaFailed(); // enrolled=false but flagged enabled — never silently skip the check
        }
        guard.enforceLockout(staff.id());
        byte[] secret = cipher.tryDecrypt(staff.mfaSecretEnc(), TokenService.mfaAad(staff)).orElseThrow(AuthErrors::mfaFailed);
        if (!guard.verifyTotp(staff.id(), secret, req.code())) {
            log.warn("MFA verification failed for staff {}", staff.id());
            guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "totp", false);
            throw AuthErrors.mfaFailed();
        }
        guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "totp", true);
        log.info("staff {} completed MFA", staff.id());
        return tokens.mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    // ── MFA enrollment (NB-042) ──────────────────────────────────────────
    // Works from either a normal access token (self-service, already logged in) or the
    // mfa_setup_required token (policy forced this staff member to enroll before
    // getting a real session) — both carry sub+tenantId, which is all enroll/confirm need.

    /** PLT-004: with MFA already on, re-enrolment needs the current TOTP or a step-up token; the active secret is untouched until confirm. */
    @Transactional(noRollbackFor = ApiException.class)
    public MfaEnrollResponse enrollMfa(Jwt callerJwt, String currentCode, String stepUpToken) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(ApiException::notFound);
        if (staff.mfaEnabled()) {
            if (stepUpToken != null && !stepUpToken.isBlank()) {
                stepUpVerifier.require(stepUpToken, staffId);
            } else {
                guard.enforceLockout(staffId);
                if (currentCode == null || staff.mfaSecretEnc() == null
                        || !guard.verifyTotp(staffId, cipher.tryDecrypt(staff.mfaSecretEnc(), TokenService.mfaAad(staff)).orElse(null), currentCode)) {
                    if (currentCode != null) {
                        guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "totp", false);
                    }
                    throw new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required",
                            "Re-enrolling an authenticator needs your current code or a step-up token.");
                }
            }
        }

        byte[] secret = totpService.generateSecret();
        repo.setPendingMfaSecret(staffId, cipher.encrypt(secret, TokenService.mfaAad(staff)));
        log.info("staff {} started MFA enrollment", staffId);
        return new MfaEnrollResponse(totpService.toBase32(secret), totpService.otpauthUri(secret, "Nabd", staff.email()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public MfaConfirmResponse confirmMfa(Jwt callerJwt, MfaConfirmRequest req) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        UUID tenantId = UUID.fromString(callerJwt.getClaimAsString("tenantId"));
        tenantContext.set(tenantId);
        Staff staff = repo.findStaffById(staffId).orElseThrow(ApiException::notFound);

        guard.enforceLockout(staffId);
        if (staff.mfaPendingSecretEnc() == null || !guard.verifyTotp(staffId, cipher.tryDecrypt(staff.mfaPendingSecretEnc(), TokenService.mfaAad(staff)).orElse(null), req.code())) {
            guard.recordAttempt(tenantId, staffId, staff.email(), "totp", false);
            throw AuthErrors.mfaFailed();
        }
        repo.confirmPendingMfa(staffId);
        repo.revokeUnusedRecoveryCodes(staffId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = OpaqueTokens.generate().substring(0, 10).toUpperCase(Locale.ROOT);
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
            throw AuthErrors.challengeInvalid();
        }
        if (!"mfa_pending".equals(challenge.getClaimAsString("purpose"))) {
            throw AuthErrors.challengeInvalid();
        }
        tenantContext.set(UUID.fromString(challenge.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(UUID.fromString(challenge.getSubject())).orElseThrow(AuthErrors::challengeInvalid);
        guard.enforceLockout(staff.id());
        String hash = OpaqueTokens.sha256Hex(req.recoveryCode().trim().toUpperCase(Locale.ROOT));
        if (!repo.consumeRecoveryCode(staff.id(), hash)) {
            guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "recovery", false);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "recovery-code-invalid", "Recovery code invalid",
                    "That recovery code is not valid.");
        }
        guard.recordAttempt(staff.tenantId(), staff.id(), staff.email(), "recovery", true);
        String roleName = repo.findRole(staff.roleId()).map(Role::name).orElse("Staff");
        auditService.record(staff.tenantId(), "staff", staff.id(), staff.name(), roleName, ip,
                "auth.recovery_code_used", "staff", staff.id(), null, null);
        TokenPairResponse t = tokens.mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
        return new RecoveryVerifyResponse(t.accessToken(), t.refreshToken(), t.expiresIn(), repo.countLiveRecoveryCodes(staff.id()));
    }
}
