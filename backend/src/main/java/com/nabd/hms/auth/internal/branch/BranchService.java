package com.nabd.hms.auth.internal.branch;

import com.nabd.hms.auth.api.OwnerAccess;
import com.nabd.hms.auth.dto.BranchListResponse;
import com.nabd.hms.auth.dto.BranchSelectRequest;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.internal.store.AuthModels.Role;
import com.nabd.hms.auth.internal.store.AuthModels.Staff;
import com.nabd.hms.auth.internal.store.AuthSessionRepository;
import com.nabd.hms.auth.internal.store.BranchRepository;
import com.nabd.hms.auth.internal.store.BranchRepository.Branch;
import com.nabd.hms.auth.internal.store.StaffDirectoryRepository;
import com.nabd.hms.auth.internal.token.AuthErrors;
import com.nabd.hms.auth.internal.token.TokenService;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.SessionRevocation;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import com.nabd.hms.shared.security.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * PLT-023: one login across the branches of a brand. A branch is a clinic (tenant), so entering one always means a new,
 * ordinary single-tenant session for the person's own staff row there: permissions, suspension and MFA policy are that
 * branch's. Nothing here widens a token, a policy or a transaction: every transaction is bound to exactly one tenant, and the
 * only cross-branch reads are the brand-scoped SECURITY DEFINER functions behind {@link BranchRepository}.
 */
@Service
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    /** Who is asking: from the caller's live access token (a switch) or from the single-use brand_select token of a sign-in. */
    private record Credential(UUID staffId, UUID tenantId, UUID sessionId, UUID tokenId, boolean mfaPassed) {
    }

    private record Source(Staff staff, String roleName, Branch target, boolean mfaPassed) {
    }

    private final BranchRepository branches;
    private final StaffDirectoryRepository staffDir;
    private final AuthSessionRepository sessions;
    private final TokenService tokens;
    private final TenantScope tenantScope;
    private final ChallengeTokenDecoder decoder;
    private final OwnerAccess ownerAccess;
    private final AuditService audit;
    private final EventPublisher events;
    private final StepUpVerifier stepUp;

    BranchService(BranchRepository branches, StaffDirectoryRepository staffDir, AuthSessionRepository sessions, TokenService tokens,
                  TenantScope tenantScope, ChallengeTokenDecoder decoder, OwnerAccess ownerAccess, AuditService audit, EventPublisher events,
                  StepUpVerifier stepUp) {
        this.branches = branches;
        this.staffDir = staffDir;
        this.sessions = sessions;
        this.tokens = tokens;
        this.tenantScope = tenantScope;
        this.decoder = decoder;
        this.ownerAccess = ownerAccess;
        this.audit = audit;
        this.events = events;
        this.stepUp = stepUp;
    }

    /** The branches the caller can enter now (their current one included); a single-branch user gets at most one. */
    @Transactional(readOnly = true)
    public BranchListResponse list(UUID tenantId, UUID staffId, boolean reportsOnly) {
        return new BranchListResponse(tenantId, reportsOnly,
                branches.branchesOf(staffId, tenantId).stream().filter(Branch::open).map(Branch::summary).toList());
    }

    /**
     * Chooses a branch after sign-in, or switches branch. The answer is a session of the chosen branch only. Not transactional
     * on purpose: the source branch and the chosen branch are two tenants, so each step runs in its own tenant-bound
     * transaction (see {@link #asSignIn}).
     */
    public TokenPairResponse select(Jwt caller, BranchSelectRequest req, String ip, String device, String userAgent) {
        Credential cred = caller != null ? fromAccessToken(caller) : fromSelectToken(req.branchSelectToken());
        UUID from = cred.tenantId(), to = req.tenantId();
        boolean reportsOnly = Boolean.TRUE.equals(req.reportsOnly());

        // 1. In the branch the person signed in to: the credential is still good, and the target is one of their own branches.
        Source src = asSignIn(from, () -> {
            Staff me = staffDir.findStaffById(cred.staffId()).filter(s -> "active".equals(s.status())).orElseThrow(AuthErrors::challengeInvalid);
            if (cred.sessionId() != null && !sessions.isSessionActive(from, cred.sessionId())) {
                throw AuthErrors.challengeInvalid(); // a session that was switched away from, signed out or revoked
            }
            Branch target = branches.branchesOf(me.id(), from).stream().filter(b -> b.tenantId().equals(to)).findFirst()
                    .orElseThrow(BranchService::notLinked); // unknown clinic, another brand's clinic, a clinic they have no row in: one answer
            return new Source(me, staffDir.findRole(me.roleId()).map(Role::name).orElse("Staff"), target,
                    cred.mfaPassed() || me.mfaEnabled() || staffDir.isOwnerLinked(me.id()));
        });
        Branch target = src.target();
        if (SessionRevocation.CLOSED_TENANT_STATUS.equals(target.tenantStatus())) {
            throw AuthErrors.tenantInactive(HttpStatus.FORBIDDEN);
        }
        if (!"active".equals(target.staffStatus())) {
            throw notLinked(); // suspended in that branch: the other branches stay open
        }

        // 2. In the chosen branch, and only there: the session, its audit row and the event.
        TokenPairResponse pair = asSignIn(to, () -> {
            if (cred.tokenId() != null && !branches.consumeToken(cred.tokenId())) {
                throw AuthErrors.challengeInvalid(); // a brand_select token works once
            }
            boolean ownerAccount = target.ownerId() != null;
            UUID staffId = target.staffId() != null ? target.staffId() : ownerAccess.findOrCreateShadowStaff(target.ownerId(), to, src.staff().name());
            Staff there = staffDir.findStaffById(staffId).filter(s -> "active".equals(s.status())).orElseThrow(BranchService::notLinked);
            // The chosen branch's own MFA rule holds: a row with MFA needs a sign-in that passed a second factor, and a row whose
            // role requires MFA but has none enrolled must sign in there once (the owner account passed its own MFA).
            if (there.mfaEnabled() ? !src.mfaPassed() : !ownerAccount && tokens.mfaRequiredByPolicy(there)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "MFA_REQUIRED", "MFA required",
                        "This branch needs your authenticator code. Sign in to it directly.");
            }
            TokenPairResponse minted = tokens.mintTokenPair(there, UUID.randomUUID(), ip, device, userAgent, null, reportsOnly);
            String role = staffDir.findRole(there.roleId()).map(Role::name).orElse("Staff");
            audit.record(to, "staff", there.id(), there.name(), role, ip, "auth.branch_selected", "staff", there.id(), null,
                    Map.of("fromTenantId", from, "reportsOnly", reportsOnly));
            if (!to.equals(from)) {
                Map<String, Object> data = new LinkedHashMap<>(Map.of("fromTenantId", from, "toTenantId", to, "status", "switched"));
                if (target.brandLoginId() != null) data.put("brandLoginId", target.brandLoginId());
                events.publish(to, DomainEventType.BRANCH_SWITCHED, there.id(), data);
            }
            return minted;
        });

        // 3. A switch signs out of the session it came from: that branch's access and refresh tokens end here.
        if (cred.sessionId() != null) {
            asSignIn(from, () -> {
                sessions.revokeFamilyOf(cred.sessionId(), "logout");
                if (!to.equals(from)) {
                    audit.record(from, "staff", src.staff().id(), src.staff().name(), src.roleName(), ip, "auth.branch_left", "staff",
                            src.staff().id(), null, Map.of("toTenantId", to));
                }
                return null;
            });
        }
        log.info("staff {} entered branch {} from {} (reportsOnly {})", cred.staffId(), to, from, reportsOnly);
        return pair;
    }

    /** The step-up "target" the owner asks /v1/auth/mfa/verify for: the confirmation is good for exactly these staff rows. */
    public static String linkTarget(List<UUID> staffIds) {
        return "staff-link:" + staffIds.stream().distinct().sorted().map(UUID::toString).collect(Collectors.joining(","));
    }

    @Transactional(readOnly = true)
    public List<BranchRepository.LinkCandidate> linkCandidates(UUID tenantId, UUID ownerStaffId, UUID staffId) {
        return branches.linkCandidates(ownerStaffId, tenantId, staffId);
    }

    /**
     * Links one staff row per branch to one login. Only the owner account of the brand may, with a step-up confirmation that
     * names these rows and is used once; that owner owns every branch named, and each of those branches gets an audit row.
     */
    @Transactional
    public UUID link(UUID tenantId, UUID ownerStaffId, List<UUID> staffIds, String stepUpToken, String ip) {
        List<UUID> ids = staffIds.stream().distinct().sorted().toList();
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, ownerStaffId, linkTarget(ids));
        BranchRepository.LinkResult r = branches.link(ownerStaffId, tenantId, ids, su.assertionId(), ip);
        switch (r.status()) {
            case "LINKED":
                log.info("staff {} linked staff rows {} to brand login {}", ownerStaffId, ids, r.brandLoginId());
                return r.brandLoginId();
            case "NOT_OWNER":
                throw problem(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the owner of the brand can link staff across branches.");
            case "NOT_FOUND":
                throw ApiException.notFound(); // an unknown staff id and a clinic of another brand answer the same
            case "ONE_PER_BRANCH":
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "ONE_PER_BRANCH", "Pick one staff account in each branch.");
            case "NOT_ELIGIBLE":
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ELIGIBLE", "Only active staff accounts can be linked. An owner account is not linked this way.");
            case "IDENTITY_MISMATCH":
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "IDENTITY_MISMATCH",
                        "These accounts do not share an email or a mobile number, so they cannot be confirmed as one person.");
            case "ALREADY_LINKED":
                throw problem(HttpStatus.CONFLICT, "ALREADY_LINKED", "These staff accounts are already linked.");
            case "STEP_UP_REUSED":
                throw problem(HttpStatus.FORBIDDEN, "STEP_UP_REUSED", "This confirmation was already used. Confirm again.");
            default:
                throw new IllegalStateException("link_brand_staff answered " + r.status());
        }
    }

    private Credential fromAccessToken(Jwt jwt) {
        try {
            return new Credential(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("tenantId")),
                    UUID.fromString(jwt.getClaimAsString("sid")), null, false);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw AuthErrors.challengeInvalid(); // not a staff access token (no tenant or session)
        }
    }

    private Credential fromSelectToken(String token) {
        try {
            Jwt t = decoder.decode(token, TokenType.BRAND_SELECT);
            if ("brand_select".equals(t.getClaimAsString("purpose"))) {
                return new Credential(UUID.fromString(t.getSubject()), UUID.fromString(t.getClaimAsString("tenantId")), null,
                        UUID.fromString(t.getId()), Boolean.TRUE.equals(t.getClaim("mfa")));
            }
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // missing, expired, tampered, wrong type or audience: the same 401 below
        }
        throw AuthErrors.challengeInvalid();
    }

    /**
     * Runs the work in a transaction bound to {@code tenantId} alone, the way /v1/auth/refresh and sign-in do. A switch arrives
     * with the caller's access token, and TenantTransactionBinder would bind every transaction of this thread to that token's
     * branch; the session being made belongs to another one. The caller's security context is put aside for the duration and
     * restored, so the binder binds nothing and TenantScope binds the one tenant named here. If a transaction were already
     * open for another tenant, TenantContext.set still refuses (TenantMismatchException).
     */
    private <T> T asSignIn(UUID tenantId, Supplier<T> work) {
        SecurityContext caller = SecurityContextHolder.getContext();
        SecurityContextHolder.clearContext();
        try {
            return tenantScope.run(tenantId, work);
        } finally {
            SecurityContextHolder.setContext(caller);
        }
    }

    private static ApiException notLinked() {
        return problem(HttpStatus.FORBIDDEN, "NOT_LINKED", "You do not have access to this branch.");
    }

    private static ApiException problem(HttpStatus status, String code, String detail) {
        return new ApiException(status, code, code, detail, Map.of("code", code));
    }
}
