package com.nabd.hms.auth.internal.token;

import com.nabd.hms.auth.AuthModels.Staff;
import com.nabd.hms.auth.AuthRepository;
import com.nabd.hms.auth.LoginGuard;
import com.nabd.hms.auth.dto.StepUpTokenResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** PLT-034 (split from AuthService): step-up tokens from a TOTP, and FIN-018 owner PIN assertions. */
@Service
public class StepUpTokenService {

    private static final Logger log = LoggerFactory.getLogger(StepUpTokenService.class);

    private final AuthRepository repo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final FieldCipher cipher;
    private final JwtEncoder jwtEncoder;
    private final LoginGuard guard;
    private final Clock clock;

    StepUpTokenService(AuthRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder, FieldCipher cipher,
                       JwtEncoder jwtEncoder, LoginGuard guard, Clock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.cipher = cipher;
        this.jwtEncoder = jwtEncoder;
        this.guard = guard;
        this.clock = clock;
    }

    /**
     * The "already authenticated, prove MFA again" branch of /auth/mfa/verify — no challenge JWT
     * involved, since the caller's own access token already establishes who they are; the TOTP
     * code is checked directly against their own secret.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public StepUpTokenResponse issueStepUpToken(Jwt callerJwt, String code, String target) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(AuthErrors::mfaFailed);

        if (staff.mfaSecretEnc() == null) {
            throw AuthErrors.mfaFailed();
        }
        guard.enforceLockout(staffId);
        byte[] secret = cipher.tryDecrypt(staff.mfaSecretEnc(), TokenService.mfaAad(staff)).orElseThrow(AuthErrors::mfaFailed);
        if (!guard.verifyTotp(staffId, secret, code)) {
            log.warn("step-up denied for staff {} — TOTP mismatch", staffId);
            guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "step_up", false);
            throw AuthErrors.mfaFailed();
        }

        Instant now = Instant.now(clock);
        Instant exp = now.plusSeconds(300);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(TokenService.ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .id(UUID.randomUUID().toString()) // IDC-013: consumed once by the gates that bind to an object (StepUpVerifier.requireFor)
                .subject(staffId.toString())
                .claim("purpose", "step_up")
                // PLT-007: bound to the minting session and tenant; StepUpVerifier compares them with the caller's token
                .claim("sid", callerJwt.getClaimAsString("sid"))
                .claim("tenantId", callerJwt.getClaimAsString("tenantId"));
        if (target != null && !target.isBlank()) claimsB.claim("target", target);
        TokenType.STEP_UP.stamp(claimsB, Audience.STEP_UP);
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claimsB.build())).getTokenValue();
        log.info("staff {} obtained a step-up token", staffId);
        return new StepUpTokenResponse(token, 300);
    }

    /**
     * FIN-018: an owner types their PIN at someone else's desk to confirm one decision there. The PIN is checked against the
     * bound tenant's active owners; a miss counts against the caller (pin_assertion_attempts, same threshold and backoff as
     * login), never against an owner's login, and the answer never says which owner or whether one exists. The token names
     * the matched owner, carries a jti (used once: DiscountApprovalService stores it on the approval it decides) and, when
     * given, the approval it is for; it is bound to the desk's session and tenant, lives 5 minutes and is accepted only by
     * StepUpVerifier.requirePinAssertion. The PIN itself is never stored or logged.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public StepUpTokenResponse issuePinAssertion(Jwt callerJwt, String pin, UUID approvalId) {
        UUID caller = UUID.fromString(callerJwt.getSubject());
        UUID tenantId = UUID.fromString(callerJwt.getClaimAsString("tenantId"));
        guard.enforceLockout(repo.countFailedPinAssertionsSinceLastSuccess(caller), repo.lastFailedPinAssertionAt(caller), caller);
        Staff owner = repo.findActiveOwners().stream().filter(o -> o.pinHash() != null && pinEncoder.matches(pin, o.pinHash())).findFirst().orElse(null);
        repo.recordPinAssertionAttempt(tenantId, caller, owner != null);
        if (owner == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "pin-invalid", "PIN not accepted", "No owner PIN matched.", java.util.Map.of("code", "PIN_INVALID"));
        }
        Instant now = Instant.now(clock);
        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(TokenService.ISSUER)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString())
                .subject(owner.id().toString())
                .claim("purpose", "pin_step_up")
                .claim("sid", callerJwt.getClaimAsString("sid"))
                .claim("tenantId", callerJwt.getClaimAsString("tenantId"));
        if (approvalId != null) claimsB.claim("approvalId", approvalId.toString());
        TokenType.STEP_UP.stamp(claimsB, Audience.STEP_UP);
        log.info("owner {} confirmed a desk action by PIN in the session of staff {}", owner.id(), caller);
        return new StepUpTokenResponse(jwtEncoder.encode(JwtEncoderParameters.from(claimsB.build())).getTokenValue(), 300);
    }
}
