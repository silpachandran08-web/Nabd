package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.patient.api.PatientRefs;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-038: household of a WhatsApp-verified phone, for the Patient Access Edge. Returns patientRef, first name, relation and age band only. */
@RestController
class InternalHouseholdController {

    private final TenantScope tenantScope;
    private final PatientRefs refs;
    private final AuditService audit;

    InternalHouseholdController(TenantScope tenantScope, PatientRefs refs, AuditService audit) {
        this.tenantScope = tenantScope;
        this.refs = refs;
        this.audit = audit;
    }

    @GetMapping("/internal/v1/tenants/{tid}/households/{phoneHash}")
    Map<String, List<PatientRefs.HouseholdMember>> household(@PathVariable UUID tid, @PathVariable String phoneHash) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        if (!phoneHash.matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "phoneHash must be 64 lowercase hex characters (SHA-256).");
        }
        return tenantScope.run(tid, () -> {
            List<PatientRefs.HouseholdMember> members = refs.household(phoneHash);
            if (members.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No household for that phone.");
            audit.record(tid, "system", null, "patient via edge", edge.serviceId(), null, "household.lookup", "household", null, null,
                    Map.of("members", members.stream().map(PatientRefs.HouseholdMember::patientRef).toList()));
            return Map.of("members", members);
        });
    }
}
