package com.nabd.hms.owner;

import com.nabd.hms.auth.AuthService;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.ApiException;
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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private static final String ISSUER = "https://api.nabd.health"; // must match AuthService.ISSUER — same signing key/issuer
    private static final String PENDING_WORKSPACE_PURPOSE = "owner_pending_workspace";

    private final OwnerRepository repo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties props;
    private final AuthService authService;

    OwnerService(OwnerRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder,
                 JwtEncoder jwtEncoder, AuthProperties props, AuthService authService) {
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
    public PendingWorkspaceTokenResponse login(OwnerLoginRequest req, String ip) {
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
        repo.recordLoginAttempt(owner.id(), email, ip, true);
        log.info("owner {} authenticated via PIN", owner.id());

        return new PendingWorkspaceTokenResponse(issuePendingWorkspaceToken(owner), 300);
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
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(300);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(owner.id().toString())
                .claim("purpose", PENDING_WORKSPACE_PURPOSE)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private void enforceIpRateLimit(String ip) {
        int recent = repo.countAttemptsFromIpSince(ip, Instant.now().minus(1, ChronoUnit.MINUTES));
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
        if (Instant.now().isBefore(lockedUntil)) {
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
}
