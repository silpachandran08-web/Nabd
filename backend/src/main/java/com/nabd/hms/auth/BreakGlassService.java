package com.nabd.hms.auth;

import com.nabd.hms.auth.AuthModels.Staff;
import com.nabd.hms.auth.dto.BreakGlassActivateRequest;
import com.nabd.hms.auth.dto.BreakGlassActivateResponse;
import com.nabd.hms.auth.dto.BreakGlassAlertResponse;
import com.nabd.hms.auth.dto.BreakGlassListResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import com.nabd.hms.shared.security.TokenType;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PLT-012: gated, read-only, 30-minute emergency access. The caller's controller already required clinical:breakGlass. */
@Service
public class BreakGlassService {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassService.class);

    private final BreakGlassRepository repo;
    private final AuthRepository authRepo;
    private final AuthService authService;
    private final AuditService auditService;
    private final EventPublisher publisher;
    private final TenantContext tenantContext;
    private final ChallengeTokenDecoder jwtDecoder;

    private final Clock clock;

    BreakGlassService(BreakGlassRepository repo, AuthRepository authRepo, AuthService authService, AuditService auditService,
                      EventPublisher publisher, TenantContext tenantContext, ChallengeTokenDecoder jwtDecoder, Clock clock) {
        this.clock = clock;
        this.repo = repo;
        this.authRepo = authRepo;
        this.authService = authService;
        this.auditService = auditService;
        this.publisher = publisher;
        this.tenantContext = tenantContext;
        this.jwtDecoder = jwtDecoder;
    }

    @Transactional
    public BreakGlassActivateResponse activate(Jwt caller, BreakGlassActivateRequest req, String stepUpToken, String ip, String device, String userAgent) {
        UUID staffId = UUID.fromString(caller.getSubject());
        UUID tenantId = UUID.fromString(caller.getClaimAsString("tenantId"));
        requireStepUp(stepUpToken, staffId, caller.getClaimAsString("sid"));
        tenantContext.set(tenantId);
        Staff staff = authRepo.findStaffById(staffId).orElseThrow(() -> forbidden("Not allowed."));

        authRepo.expireStaleBreakGlass(staffId);
        if (repo.hasActive(staffId)) {
            throw new ApiException(HttpStatus.CONFLICT, "already-active", "Emergency access already active",
                    "You already have an active emergency access grant.");
        }
        Instant expiresAt = Instant.now(clock).plus(BreakGlassPolicy.MINUTES, ChronoUnit.MINUTES);
        UUID sessionId = UUID.randomUUID();
        UUID grantId;
        try {
            grantId = repo.insert(tenantId, staffId, req.reasonCode(), req.reasonText(), req.patientId(), expiresAt, sessionId);
        } catch (org.springframework.dao.DuplicateKeyException e) { // uq_break_glass_one_active: lost the race
            throw new ApiException(HttpStatus.CONFLICT, "already-active", "Emergency access already active",
                    "You already have an active emergency access grant.");
        }

        // reasonText is deliberately not in the audit row or the log: free text can carry PHI.
        auditService.record(tenantId, "staff", staffId, staff.name(), "break-glass", ip, "auth.break_glass_activated",
                "break_glass_grant", grantId, null, Map.of("reasonCode", req.reasonCode(), "expiresAt", expiresAt.toString()));
        log.warn("BREAK-GLASS activated: grant {} staff {} tenant {} reasonCode {}", grantId, staffId, tenantId, req.reasonCode());  // log-phi-ok: reasonCode is a 4-value enum code (validated @Pattern), not free text

        Map<String, Object> payload = Map.of("tenantId", tenantId.toString(), "grantId", grantId.toString(),
                "staffId", staffId.toString(), "reasonCode", req.reasonCode(), "expiresAt", expiresAt.toString(),
                "activatedAt", Instant.now(clock).toString());
        publisher.publish(tenantId, BreakGlassPolicy.ACTIVATED_EVENT, payload);
        publisher.publish(tenantId, BreakGlassPolicy.OWNER_WHATSAPP_EVENT, payload);

        TokenPairResponse tokens = authService.mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent,
                new BreakGlassSession(grantId, sessionId, expiresAt));
        return new BreakGlassActivateResponse(grantId, expiresAt, tokens.accessToken(), tokens.refreshToken());
    }

    /** Holder ends their own; anyone else needs staff:edit. Already-ended grants are a no-op (idempotent). */
    @Transactional
    public void deactivate(Jwt caller, UUID grantId) {
        UUID tenantId = UUID.fromString(caller.getClaimAsString("tenantId"));
        UUID callerId = UUID.fromString(caller.getSubject());
        tenantContext.set(tenantId);
        BreakGlassRepository.Grant grant = repo.find(tenantId, grantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such emergency access grant."));
        boolean holder = grant.staffId().equals(callerId);
        List<String> perms = caller.getClaimAsStringList("permissions");
        if (!holder && (perms == null || !perms.contains("staff:edit"))) {
            throw forbidden("Only the holder or a user who can edit staff may end this emergency access.");
        }
        if (!grant.active()) {
            return;
        }
        repo.deactivate(tenantId, grantId, callerId, holder ? "manual" : "revoked_by_owner");
        if (grant.sessionId() != null) {
            repo.revokeSession(tenantId, grant.sessionId());
        }
        auditService.record(tenantId, "staff", callerId, "", "", null, "auth.break_glass_deactivated",
                "break_glass_grant", grantId, null, null);
    }

    @Transactional
    public BreakGlassListResponse listActive(UUID tenantId) {
        tenantContext.set(tenantId);
        return new BreakGlassListResponse(repo.listActive(tenantId));
    }

    @Transactional
    public BreakGlassAlertResponse myAlerts(UUID tenantId, UUID staffId) {
        tenantContext.set(tenantId);
        return new BreakGlassAlertResponse(repo.alertsFor(tenantId, staffId));
    }

    /** A step-up token with purpose break_glass, for this staff member, bound to the caller's session. */
    private void requireStepUp(String token, UUID staffId, String sid) {
        if (token == null || token.isBlank()) {
            throw stepUpRequired();
        }
        Jwt t;
        try {
            t = jwtDecoder.decode(token, TokenType.STEP_UP);
        } catch (JwtException e) {
            throw stepUpRequired();
        }
        if (!"break_glass".equals(t.getClaimAsString("purpose")) || !staffId.toString().equals(t.getSubject())
                || sid == null || !sid.equals(t.getClaimAsString("sid"))) {
            throw stepUpRequired();
        }
    }

    private static ApiException stepUpRequired() {
        return new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required",
                "Emergency access needs a fresh MFA confirmation (X-Step-Up-Token, purpose break_glass).");
    }

    private static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", detail);
    }
}
