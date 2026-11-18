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

/** ACC-038: household of a WhatsApp-verified phone, for the Patient Access Edge. Returns patientRef, first name, relation and age band only.
 * COM-044: plus each member's bookable package sessions ('Book session N of M'): ids and counts only, never the package name. */
@RestController
class InternalHouseholdController {

    @io.swagger.v3.oas.annotations.media.Schema(name = "InternalHouseholdPackageSession")
    record PackageSession(String patientRef, UUID instanceId, int nextSession, int totalSessions, List<UUID> eligibleDoctorIds) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "InternalHousehold")
    record Household(List<PatientRefs.HouseholdMember> members, List<PackageSession> packageSessions) {
    }

    private final TenantScope tenantScope;
    private final PatientRefs refs;
    private final AuditService audit;
    private final com.nabd.hms.shared.booking.PackageSessionPort packages;

    InternalHouseholdController(TenantScope tenantScope, PatientRefs refs, AuditService audit, com.nabd.hms.shared.booking.PackageSessionPort packages) {
        this.packages = packages;
        this.tenantScope = tenantScope;
        this.refs = refs;
        this.audit = audit;
    }

    @GetMapping("/internal/v1/tenants/{tid}/households/{phoneHash}")
    Household household(@PathVariable UUID tid, @PathVariable String phoneHash) {
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
            List<PackageSession> sessions = members.stream().flatMap(m -> refs.patientIdFor(m.patientRef()).stream()
                    .flatMap(id -> packages.bookable(id).stream())
                    .map(b -> new PackageSession(m.patientRef(), b.instanceId(), b.nextSession(), b.totalSessions(), b.eligibleDoctorIds()))).toList();
            return new Household(members, sessions);
        });
    }
}
