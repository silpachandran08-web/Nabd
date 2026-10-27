package com.nabd.hms.patient.proxy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IDC-009: the caregiver side for the Patient Access Edge (service identity, tenant in the path). The spec's /v1/me/family/members
 * (patient session) waits on the patient session; the Edge already knows the caregiver's reference from the verified phone.
 */
@RestController
class InternalFamilyController {

    @Schema(name = "FamilyMemberAdd")
    record AddRequest(@NotBlank @Size(max = 64) String caregiverRef, @NotBlank @Size(max = 120) String name, @NotNull @Past LocalDate dob,
                      @NotBlank @Pattern(regexp = "^[a-z_]{2,20}$") String relation, @Size(max = 20) String phone,
                      @Pattern(regexp = "male|female|other") String sex) {
    }

    @Schema(name = "FamilyMemberAdded")
    record AddResponse(String memberRef, String proxyStatus) {
    }

    @Schema(name = "FamilyMemberResend")
    record ResendRequest(@NotBlank @Size(max = 64) String caregiverRef) {
    }

    @Schema(name = "FamilyMemberList")
    record MemberList(List<ProxyService.Member> members) {
    }

    private final TenantScope tenantScope;
    private final ProxyService service;

    InternalFamilyController(TenantScope tenantScope, ProxyService service) {
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/family/members")
    @ResponseStatus(HttpStatus.CREATED)
    AddResponse add(@PathVariable UUID tid, @Valid @RequestBody AddRequest r) {
        ServiceIdentity edge = allowed(tid);
        return tenantScope.run(tid, () -> {
            ProxyService.Added a = service.addMember(r.caregiverRef(), r.name(), r.dob(), r.relation(), r.phone(), r.sex() == null ? "other" : r.sex(), edge.serviceId());
            return new AddResponse(a.memberRef(), a.proxyStatus());
        });
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/family/members/{memberRef}/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> resend(@PathVariable UUID tid, @PathVariable String memberRef, @Valid @RequestBody ResendRequest r) {
        ServiceIdentity edge = allowed(tid);
        tenantScope.run(tid, () -> service.resend(r.caregiverRef(), memberRef, edge.serviceId()));
        return Map.of("sent", true);
    }

    @GetMapping("/internal/v1/tenants/{tid}/family/members")
    MemberList members(@PathVariable UUID tid, @RequestParam @Size(max = 64) String caregiverRef) {
        allowed(tid);
        return tenantScope.run(tid, () -> new MemberList(service.members(caregiverRef)));
    }

    /** The scope gate for a patient surface: 200 {relation, scope, status}, or 403 proxy-consent-pending / proxy-scope-insufficient / not-a-proxy. */
    @GetMapping("/internal/v1/tenants/{tid}/family/members/{memberRef}/access")
    ProxyService.Access access(@PathVariable UUID tid, @PathVariable String memberRef, @RequestParam @Size(max = 64) String caregiverRef,
                               @RequestParam(defaultValue = "book") @Pattern(regexp = "book|records") String need) {
        allowed(tid);
        return tenantScope.run(tid, () -> service.access(caregiverRef, memberRef, "records".equals(need)));
    }

    private static ServiceIdentity allowed(UUID tid) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        return edge;
    }
}
