package com.nabd.hms.auth.internal.session;

import com.nabd.hms.auth.AuthModels.SessionRow;
import com.nabd.hms.auth.AuthModels.Staff;
import com.nabd.hms.auth.AuthRepository;
import com.nabd.hms.auth.LoginGuard;
import com.nabd.hms.auth.dto.RefreshRequest;
import com.nabd.hms.auth.dto.SessionResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.dto.UnlockRequest;
import com.nabd.hms.auth.internal.token.AuthErrors;
import com.nabd.hms.auth.internal.token.TokenService;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** PLT-034 (split from AuthService): refresh rotation, sign-out, device sessions, idle unlock and the sessions other modules start. */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final AuthRepository repo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final LoginGuard guard;
    private final TokenService tokens;
    private final Clock clock;

    @Value("${app.auth.absolute-session-hours:12}")
    private int absoluteSessionHours; // PLT-007: refresh-family cap (one shift); per-tenant SSO policy later

    SessionService(AuthRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder, LoginGuard guard,
                   TokenService tokens, Clock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.guard = guard;
        this.tokens = tokens;
        this.clock = clock;
    }

    // ── idle-timeout unlock (NB-044) ─────────────────────────────────────
    // Client-side lock only — the underlying session/access token is untouched, so draft state in
    // the page (or already-autosaved backend rows) survives the lock exactly as if nothing happened.

    @Transactional(noRollbackFor = ApiException.class)
    public void unlock(Jwt callerJwt, UnlockRequest req) {
        UUID staffId = UUID.fromString(callerJwt.getSubject());
        tenantContext.set(UUID.fromString(callerJwt.getClaimAsString("tenantId")));
        Staff staff = repo.findStaffById(staffId).orElseThrow(ApiException::notFound);
        guard.enforceLockout(staffId); // 423 before the PIN is even looked at
        if (staff.pinHash() == null || !pinEncoder.matches(req.pin(), staff.pinHash())) {
            guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "unlock", false);
            throw AuthErrors.invalidCredentials();
        }
        guard.recordAttempt(staff.tenantId(), staffId, staff.email(), "unlock", true);
    }

    /** PLT-005: invite accept lands the new staff member signed in, unless their role needs MFA first (then a setup token). */
    @Transactional
    public Object sessionAfterInviteAccept(UUID tenantId, UUID staffId, String ip, String device, String userAgent) {
        tenantContext.set(tenantId);
        Staff staff = repo.findStaffById(staffId)
                .orElseThrow(() -> new IllegalStateException("staff " + staffId + " vanished right after invite accept"));
        if (!staff.mfaEnabled() && tokens.mfaRequiredByPolicy(staff)) {
            return tokens.issueMfaSetupRequired(staff);
        }
        return tokens.mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    /** Called from OwnerService once the owner account is through its own MFA — lands the owner in the clinic. */
    @Transactional
    public TokenPairResponse issueTokensForStaff(UUID tenantId, UUID staffId, String ip, String device, String userAgent) {
        tenantContext.set(tenantId);
        if (tokens.tenantInactive(tenantId)) { // PLT-007: workspace select into a suspended/offboarded clinic
            throw AuthErrors.tenantInactive(HttpStatus.FORBIDDEN);
        }
        Staff staff = repo.findStaffById(staffId)
                .orElseThrow(() -> new IllegalStateException("staff " + staffId + " vanished right after invite accept"));
        return tokens.mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    // noRollbackFor: reuse detection's revokeFamily() write must survive the ApiException thrown
    // right after it, or the default rollback-on-RuntimeException undoes the very revocation this
    // method exists to perform.
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPairResponse refresh(RefreshRequest req, String ip, String device, String userAgent) {
        String tokenHash = OpaqueTokens.sha256Hex(req.refreshToken());
        SessionRow session = repo.findSessionByTokenHash(tokenHash).orElseThrow(AuthErrors::invalidRefresh);

        tenantContext.set(session.tenantId());

        // PLT-007: checked before reuse detection, since suspension revokes the sessions this would read as reuse
        if (tokens.tenantInactive(session.tenantId())) {
            throw AuthErrors.tenantInactive(HttpStatus.UNAUTHORIZED);
        }
        if ("max_age".equals(session.revokedReason())) {
            throw AuthErrors.sessionMaxAge();
        }
        if (session.revokedAt() != null) {
            // token already used once (or already revoked) and is being presented again — compromise signal
            log.warn("refresh-token reuse detected — revoking session family {} (staff {}, tenant {})",
                    session.familyId(), session.staffId(), session.tenantId());
            repo.revokeFamily(session.familyId(), "reuse_detected");
            throw AuthErrors.invalidRefresh();
        }
        if (session.expiresAt().isBefore(Instant.now(clock))) {
            throw AuthErrors.invalidRefresh();
        }
        // PLT-007: absolute lifetime of the family; unlock never mints, so an idle-lock unlock cannot extend it
        if (!Instant.now(clock).isBefore(repo.familyStartedAt(session.tenantId(), session.id()).plus(absoluteSessionHours, ChronoUnit.HOURS))) {
            repo.revokeFamily(session.familyId(), "max_age");
            throw AuthErrors.sessionMaxAge();
        }

        Staff staff = repo.findStaffById(session.staffId()).orElseThrow(AuthErrors::invalidRefresh);
        if (!"active".equals(staff.status())) {
            throw AuthErrors.invalidRefresh();
        }
        // PLT-005: the invariant is checked at every mint; a privileged session without MFA ends here and the next sign-in enrols.
        // Owner shadow rows are exempt: the owner account passes its own MFA before entering a clinic.
        if (!staff.mfaEnabled() && tokens.privilegedByPolicy(staff, repo.findRole(staff.roleId()).orElseThrow(AuthErrors::invalidRefresh))
                && !repo.isOwnerLinked(staff.id())) {
            repo.revokeFamily(session.familyId(), "admin_revoke");
            throw AuthErrors.invalidRefresh();
        }

        repo.revokeSession(session.id(), "rotated");
        log.debug("session refreshed for staff {} (family {})", staff.id(), session.familyId());
        return tokens.mintTokenPair(staff, session.familyId(), ip, device, userAgent);
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
            throw ApiException.notFound();
        }
        log.info("staff {} revoked their own session {}", staffId, sessionId);
    }
}
