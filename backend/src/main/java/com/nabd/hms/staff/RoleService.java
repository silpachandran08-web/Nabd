package com.nabd.hms.staff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.staff.dto.DelegationRequest;
import com.nabd.hms.staff.dto.DelegationResponse;
import com.nabd.hms.staff.dto.RoleResponse;
import com.nabd.hms.staff.dto.RoleWriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    RoleService(RoleRepository repo, TenantContext tenantContext, ObjectMapper objectMapper, AuditService auditService) {
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
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public RoleResponse create(UUID tenantId, UUID callerStaffId, RoleWriteRequest req, List<String> callerPermissions) {
        tenantContext.set(tenantId);
        requireNoPrivilegeEscalation(tenantId, callerStaffId, req.grants(), callerPermissions);

        UUID id = UUID.randomUUID();
        repo.insert(id, tenantId, req.name(), writeGrantsJson(req.grants()), req.mfaRequired());
        log.info("role {} '{}' created by {} (tenant {})", id, req.name(), callerStaffId, tenantId);
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow();
    }

    @Transactional
    public RoleResponse update(UUID tenantId, UUID callerStaffId, UUID id, RoleWriteRequest req, List<String> callerPermissions) {
        tenantContext.set(tenantId);
        RoleRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        if (current.builtIn()) {
            throw builtInImmutable();
        }
        requireNoPrivilegeEscalation(tenantId, callerStaffId, req.grants(), callerPermissions);

        repo.update(tenantId, id, req.name(), writeGrantsJson(req.grants()), req.mfaRequired());
        log.info("role {} updated by {} (tenant {})", id, callerStaffId, tenantId);
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow();
    }

    /** "Attempted edit of a built-in role, or a grant beyond the editor's own permissions... blocked" — api/openapi.yaml. */
    private void requireNoPrivilegeEscalation(UUID tenantId, UUID callerStaffId, List<ModuleGrant> requestedGrants,
                                               List<String> callerPermissions) {
        List<String> requested = GrantsFlattener.flatten(requestedGrants);
        if (!callerPermissions.containsAll(requested)) {
            log.warn("privilege escalation blocked: staff {} (tenant {}) requested grants beyond their own permissions",
                    callerStaffId, tenantId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "privilege-escalation", "Grant exceeds your own permissions",
                    "You cannot grant a permission you do not hold yourself.");
        }
    }

    // ── delegations (NB-057) ─────────────────────────────────────────────

    @Transactional
    public DelegationResponse createDelegation(UUID tenantId, UUID callerStaffId, DelegationRequest req, List<String> callerPermissions) {
        tenantContext.set(tenantId);
        RoleRow delegatedRole = repo.findById(tenantId, req.delegatedRoleId()).orElseThrow(this::notFound);
        if (req.expiresAt().isAfter(Instant.now().plus(MAX_DELEGATION_WINDOW))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "delegation-window-too-long", "Window too long",
                    "A delegation can't run longer than 30 days — create a new one if the cover extends past that.");
        }
        requireNoPrivilegeEscalation(tenantId, callerStaffId, readGrants(delegatedRole.grantsJson()), callerPermissions);

        UUID id = repo.insertDelegation(tenantId, req.staffId(), req.delegatedRoleId(), callerStaffId, req.reason(), req.expiresAt());
        DelegationRow row = repo.findDelegation(tenantId, id).orElseThrow();
        audit(tenantId, callerStaffId, "staff.delegation_granted", id, null, row);
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
        repo.findDelegation(tenantId, id).orElseThrow(this::notFound);
        repo.revokeDelegation(tenantId, id);
        audit(tenantId, callerStaffId, "staff.delegation_revoked", id, null, null);
    }

    private DelegationResponse toDelegationResponse(DelegationRow row) {
        return new DelegationResponse(row.id(), row.staffId(), row.delegatedRoleId(), row.delegatedRoleName(),
                row.grantedBy(), row.reason(), row.startsAt(), row.expiresAt(), row.active(), row.revokedAt(), row.revokedReason());
    }

    private void audit(UUID tenantId, UUID callerStaffId, String action, UUID entityId, Object before, Object after) {
        RoleRepository.ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                action, "role_delegation", entityId, before, after);
    }

    private RoleResponse toResponse(RoleRow row) {
        return new RoleResponse(row.id(), row.name(), row.builtIn(), readGrants(row.grantsJson()), row.mfaRequired());
    }

    private List<ModuleGrant> readGrants(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ModuleGrant>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("malformed grants JSON", e);
        }
    }

    private String writeGrantsJson(List<ModuleGrant> grants) {
        try {
            return objectMapper.writeValueAsString(grants);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize grants", e);
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }

    private ApiException builtInImmutable() {
        return new ApiException(HttpStatus.BAD_REQUEST, "built-in-role-immutable", "Built-in role cannot be edited",
                "Built-in roles cannot be modified; create a custom role instead.");
    }
}
