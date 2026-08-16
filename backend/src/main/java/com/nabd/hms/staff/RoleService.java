package com.nabd.hms.staff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.staff.dto.RoleResponse;
import com.nabd.hms.staff.dto.RoleWriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.RoleRow;

@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final RoleRepository repo;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    RoleService(RoleRepository repo, TenantContext tenantContext, ObjectMapper objectMapper) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
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
        repo.insert(id, tenantId, req.name(), writeGrantsJson(req.grants()));
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

        repo.update(tenantId, id, req.name(), writeGrantsJson(req.grants()));
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

    private RoleResponse toResponse(RoleRow row) {
        return new RoleResponse(row.id(), row.name(), row.builtIn(), readGrants(row.grantsJson()));
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
