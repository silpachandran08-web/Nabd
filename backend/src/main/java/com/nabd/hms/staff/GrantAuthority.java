package com.nabd.hms.staff;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.RoleRow;
import static com.nabd.hms.staff.StaffRoleModels.StaffRow;

/**
 * PLT-015: the one place "no grant beyond your own" is decided, for role edit, staff patch and invite.
 * Staff patch/invite judge the caller on their permanent role (never token grants, which can carry a loan).
 */
@Component
class GrantAuthority {

    static final String ALL_PATIENTS = "all_clinic_patients";

    private final RoleRepository roles;
    private final ObjectMapper objectMapper;

    GrantAuthority(RoleRepository roles, ObjectMapper objectMapper) {
        this.roles = roles;
        this.objectMapper = objectMapper;
    }

    /** True when {@code requested} contains something {@code held} lacks. */
    static boolean exceeds(Collection<String> requested, Collection<String> held) {
        return !new HashSet<>(held).containsAll(requested);
    }

    /** Throws 403 unless the caller may give {@code target} (a role, scope, field grants) to somebody. Null/unchanged parts are skipped. */
    void requireAssignable(UUID tenantId, StaffRow caller, RoleRow role, String scope, List<String> fieldGrants) {
        RoleRow base = roles.findById(tenantId, caller.roleId()).orElseThrow();
        if (isOwner(base)) {
            return; // Owner holds every grant
        }
        if (role != null) {
            if (isOwner(role)) {
                throw forbidden("owner-role-restricted", "OWNER_ROLE_RESTRICTED", "Only an Owner can assign or change the Owner role.");
            }
            if (exceeds(GrantsFlattener.flatten(grants(role.grantsJson())), GrantsFlattener.flatten(grants(base.grantsJson())))) {
                throw exceeded("You cannot assign a role with permissions you do not hold yourself.");
            }
        }
        if (ALL_PATIENTS.equals(scope) && !ALL_PATIENTS.equals(caller.scope())) {
            throw exceeded("You cannot widen patient scope beyond your own.");
        }
        if (fieldGrants != null && exceeds(fieldGrants, caller.fieldGrants())) {
            throw exceeded("You cannot grant field access you do not hold yourself.");
        }
    }

    static boolean isOwner(RoleRow role) {
        return role.builtIn() && "Owner".equals(role.name());
    }

    private List<ModuleGrant> grants(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ModuleGrant>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("malformed grants JSON", e);
        }
    }

    private ApiException exceeded(String detail) {
        return forbidden("grant-exceeds-caller", "GRANT_EXCEEDS_CALLER", detail);
    }

    private ApiException forbidden(String slug, String title, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, slug, title, detail);
    }
}
