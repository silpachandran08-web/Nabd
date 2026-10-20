package com.nabd.hms.staff;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.StepUpVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.RoleRow;

/** PLT-014: the rules for who may delegate what. Judged on the grantor's permanent role, never on token grants. */
@Component
class DelegationGuard {

    private final RoleRepository repo;
    private final StepUpVerifier stepUpVerifier;
    private final ObjectMapper objectMapper;

    DelegationGuard(RoleRepository repo, StepUpVerifier stepUpVerifier, ObjectMapper objectMapper) {
        this.repo = repo;
        this.stepUpVerifier = stepUpVerifier;
        this.objectMapper = objectMapper;
    }

    void check(UUID tenantId, UUID callerId, UUID targetId, RoleRow role, String stepUpToken) {
        if (callerId.equals(targetId)) {
            throw forbidden("self-delegation", "Self-delegation not allowed", "You cannot delegate a role to yourself.");
        }
        if (repo.findActiveDelegatedRoleIds(callerId).contains(role.id())) {
            throw forbidden("chained-delegation", "Chained delegation not allowed",
                    "A role you hold on loan cannot be delegated onward.");
        }
        Set<String> base = new HashSet<>(GrantsFlattener.flatten(grants(repo.findBaseRoleGrants(tenantId, callerId))));
        if (!base.containsAll(GrantsFlattener.flatten(grants(role.grantsJson())))) {
            throw forbidden("grant-exceeds-base-role", "Grant exceeds your base role",
                    "You can only delegate permissions your own permanent role holds.");
        }
        if (role.builtIn() && "Owner".equals(role.name())) {
            stepUpVerifier.require(stepUpToken, callerId);
        }
    }

    private List<ModuleGrant> grants(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ModuleGrant>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("malformed grants JSON", e);
        }
    }

    private ApiException forbidden(String slug, String title, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, slug, title, detail);
    }
}
