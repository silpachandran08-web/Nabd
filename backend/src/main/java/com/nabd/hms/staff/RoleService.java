package com.nabd.hms.staff;

import com.nabd.hms.iam.api.ActorDirectory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.auth.MfaPolicy;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.staff.dto.DelegationRequest;
import com.nabd.hms.staff.dto.DelegationResponse;
import com.nabd.hms.staff.dto.RoleResponse;
import com.nabd.hms.staff.dto.RoleWriteRequest;
import com.nabd.hms.staff.presets.RolePresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.DelegationRow;
import static com.nabd.hms.staff.StaffRoleModels.RoleRow;

@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    // ponytail: fixed 30-day ceiling on any one delegation; raise (or make configurable) if a
    // real leave-cover case needs longer, but a temp grant with no upper bound isn't temporary.
    private static final Duration MAX_DELEGATION_WINDOW = Duration.ofDays(30);

    private final RoleRepository repo;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final DelegationGuard delegationGuard;
    private final EventPublisher eventPublisher;

    static final String DELEGATION_EVENT = "iam.delegation.changed.v1";

    private final Clock clock;

    private final ActorDirectory actors;

    RoleService(RoleRepository repo, TenantContext tenantContext, ObjectMapper objectMapper, AuditService auditService,
                DelegationGuard delegationGuard, EventPublisher eventPublisher, Clock clock, ActorDirectory actors) {
        this.actors = actors;
        this.clock = clock;
        this.delegationGuard = delegationGuard;
        this.eventPublisher = eventPublisher;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public List<RoleResponse> list(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.list(tenantId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public RoleResponse get(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public RoleResponse create(UUID tenantId, UUID callerStaffId, RoleWriteRequest req) {
        tenantContext.set(tenantId);
        requireNoPrivilegeEscalation(tenantId, callerStaffId, req.grants());
        requireMfaInvariant(req);

        UUID id = UUID.randomUUID();
        repo.insert(id, tenantId, req.name(), writeGrantsJson(req.grants()), Boolean.TRUE.equals(req.mfaRequired()));
        log.info("role {} created by {} (tenant {})", id, callerStaffId, tenantId);
        roleChanged(tenantId, id, "created");
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow();
    }

    /** OPS-015: the role id and what happened; consumers re-read grants (the role name is not carried). */
    private void roleChanged(UUID tenantId, UUID roleId, String status) {
        eventPublisher.publish(tenantId, com.nabd.hms.common.DomainEventType.ROLE_CHANGED, roleId,
                java.util.Map.of("roleId", roleId, "status", status));
    }

    /** PLT-007 AC3: same mechanism as StaffService's role reassignment (revoke + iam.session.revoked.v1 per holder). */
    private void sessionsRevoked(UUID tenantId, List<UUID> staffIds) {
        for (UUID staffId : staffIds) {
            eventPublisher.publish(tenantId, com.nabd.hms.common.DomainEventType.SESSION_REVOKED, staffId,
                    Map.of("staffId", staffId, "scope", "all_sessions", "reasonCode", "role_changed", "status", "revoked"));
        }
    }

    @Transactional
    public RoleResponse update(UUID tenantId, UUID callerStaffId, UUID id, RoleWriteRequest req) {
        tenantContext.set(tenantId);
        RoleRow current = repo.findById(tenantId, id).orElseThrow(ApiException::notFound);
        if (current.builtIn()) {
            throw builtInImmutable();
        }
        requireNoPrivilegeEscalation(tenantId, callerStaffId, req.grants());
        requireMfaInvariant(req);

        repo.update(tenantId, id, req.name(), writeGrantsJson(req.grants()),
                req.mfaRequired() == null ? current.mfaRequired() : req.mfaRequired());
        log.info("role {} updated by {} (tenant {})", id, callerStaffId, tenantId);
        roleChanged(tenantId, id, "updated");
        sessionsRevoked(tenantId, repo.revokeHolderSessions(tenantId, id));
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow();
    }

    /** PLT-016: grants back to the current RolePresets definition; Owner only; audited with before/after. */
    @Transactional
    public RoleResponse resetToPreset(UUID tenantId, UUID callerStaffId, UUID id) {
        tenantContext.set(tenantId);
        if (!repo.isOwner(tenantId, callerStaffId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-only", "OWNER_ONLY", "Only the Owner can reset a role to its preset.");
        }
        RoleRow current = repo.findById(tenantId, id).orElseThrow(ApiException::notFound);
        RolePresets.Preset preset = Optional.ofNullable(current.presetCode()).flatMap(RolePresets::byCode)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "not-a-preset", "NOT_A_PRESET",
                        "This is a custom role; only preset roles can be reset."));
        repo.resetToPreset(tenantId, id, writeGrantsJson(preset.grants()), RolePresets.VERSION);
        roleChanged(tenantId, id, "reset_to_preset");
        sessionsRevoked(tenantId, repo.revokeHolderSessions(tenantId, id));
        RoleRow after = repo.findById(tenantId, id).orElseThrow();
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow(ApiException::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null, "role.reset_to_preset", "role", id,
                Map.of("presetVersion", String.valueOf(current.presetVersion()), "grants", readGrants(current.grantsJson())),
                Map.of("presetVersion", String.valueOf(after.presetVersion()), "grants", readGrants(after.grantsJson())));
        log.info("role {} reset to preset {} v{} by {} (tenant {})", id, preset.code(), RolePresets.VERSION, callerStaffId, tenantId); // log-phi-ok: preset code is a system constant
        return toResponse(after);
    }

    /** PLT-005: a role holding :approve or :refundDiscount always requires MFA; asking for false is refused, not ignored. */
    private void requireMfaInvariant(RoleWriteRequest req) {
        if (Boolean.FALSE.equals(req.mfaRequired()) && MfaPolicy.privilegedGrants(GrantsFlattener.flatten(req.grants()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "mfa-required-by-policy", "MFA required by policy",
                    "Roles that can approve, refund or discount always require MFA; mfaRequired cannot be false.");
        }
    }

    /** "Attempted edit of a built-in role, or a grant beyond the editor's own permissions... blocked" — api/openapi.yaml. */
    private void requireNoPrivilegeEscalation(UUID tenantId, UUID callerStaffId, List<ModuleGrant> requestedGrants) {
        // PLT-016: the Owner holds every grant (as in GrantAuthority), even though its MF-52 preset lacks clinical:create/edit/approve
        if (repo.isOwner(tenantId, callerStaffId)) {
            return;
        }
        // PLT-015: judged on the permanent role, never token grants (a delegation loan must not be baked into a role)
        List<String> permanent = GrantsFlattener.flatten(readGrants(repo.findBaseRoleGrants(tenantId, callerStaffId)));
        if (GrantAuthority.exceeds(GrantsFlattener.flatten(requestedGrants), permanent)) {
            log.warn("privilege escalation blocked: staff {} (tenant {}) requested grants beyond their own permissions",
                    callerStaffId, tenantId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "privilege-escalation", "Grant exceeds your own permissions",
                    "You cannot grant a permission you do not hold yourself.");
        }
    }

    // ── delegations (NB-057) ─────────────────────────────────────────────

    @Transactional
    public DelegationResponse createDelegation(UUID tenantId, UUID callerStaffId, DelegationRequest req, String stepUpToken) {
        tenantContext.set(tenantId);
        RoleRow delegatedRole = repo.findById(tenantId, req.delegatedRoleId()).orElseThrow(ApiException::notFound);
        if (req.expiresAt().isAfter(Instant.now(clock).plus(MAX_DELEGATION_WINDOW))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "delegation-window-too-long", "Window too long",
                    "A delegation can't run longer than 30 days — create a new one if the cover extends past that.");
        }
        // unknown id (FK would 500) or another tenant's staff (FK would pass) -> 404
        actors.findActor(tenantId, req.staffId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such staff member."));
        delegationGuard.check(tenantId, callerStaffId, req.staffId(), delegatedRole, stepUpToken);

        UUID id = repo.insertDelegation(tenantId, req.staffId(), req.delegatedRoleId(), callerStaffId, req.reason(), req.expiresAt());
        DelegationRow row = repo.findDelegation(tenantId, id).orElseThrow();
        audit(tenantId, callerStaffId, "role.delegation_created", id, null, auditView(row));
        changed(tenantId, row, "created");
        return toDelegationResponse(row);
    }

    @Transactional
    public List<DelegationResponse> listDelegations(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listDelegations(tenantId).stream().map(this::toDelegationResponse).toList();
    }

    @Transactional
    public void revokeDelegation(UUID tenantId, UUID callerStaffId, UUID id) {
        tenantContext.set(tenantId);
        DelegationRow row = repo.findDelegation(tenantId, id).orElseThrow(ApiException::notFound);
        repo.revokeDelegation(tenantId, id);
        audit(tenantId, callerStaffId, "role.delegation_revoked", id, auditView(row), null);
        changed(tenantId, row, "revoked");
    }

    /** Kills the target's sessions so the new grant set applies on their very next request, and announces the change. */
    private void changed(UUID tenantId, DelegationRow row, String change) {
        repo.revokeSessions(tenantId, row.staffId(), "admin_revoke");
        eventPublisher.publish(tenantId, DELEGATION_EVENT, java.util.Map.of("tenantId", tenantId.toString(),
                "delegationId", row.id().toString(), "staffId", row.staffId().toString(), "change", change));
    }

    private java.util.Map<String, Object> auditView(DelegationRow row) {
        return java.util.Map.of("staffId", row.staffId(), "delegatedRoleId", row.delegatedRoleId(),
                "delegatedRoleName", row.delegatedRoleName(), "expiresAt", row.expiresAt().toString());
    }

    private DelegationResponse toDelegationResponse(DelegationRow row) {
        return new DelegationResponse(row.id(), row.staffId(), row.delegatedRoleId(), row.delegatedRoleName(),
                row.grantedBy(), row.reason(), row.startsAt(), row.expiresAt(), row.active(Instant.now(clock)), row.revokedAt(), row.revokedReason());
    }

    private void audit(UUID tenantId, UUID callerStaffId, String action, UUID entityId, Object before, Object after) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow(ApiException::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                action, "role_delegation", entityId, before, after);
    }

    private RoleResponse toResponse(RoleRow row) {
        List<ModuleGrant> grants = readGrants(row.grantsJson());
        boolean effective = row.mfaRequired() || MfaPolicy.privilegedRole(row.builtIn(), GrantsFlattener.flatten(grants));
        return new RoleResponse(row.id(), row.name(), row.builtIn(), grants, effective, row.presetCode(), row.presetVersion());
    }

    private List<ModuleGrant> readGrants(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ModuleGrant>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("malformed grants JSON", e);
        }
    }

    private String writeGrantsJson(List<ModuleGrant> grants) {
        try {
            return objectMapper.writeValueAsString(grants);
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to serialize grants", e);
        }
    }


    private ApiException builtInImmutable() {
        return new ApiException(HttpStatus.BAD_REQUEST, "built-in-role-immutable", "Built-in role cannot be edited",
                "Built-in roles cannot be modified; create a custom role instead.");
    }
}
