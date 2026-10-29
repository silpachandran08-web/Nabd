package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** ACC-043: chat registration for the Patient Access Edge (service identity, tenant in the path, Idempotency-Key on writes). */
@RestController
class ChatRegistrationController {

    @Schema(name = "ChatPatientCreate")
    record CreateRequest(@NotNull @AssertTrue Boolean phoneVerified, @NotBlank @Size(max = 20) String phone,
                         @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String phoneHash,
                         @NotBlank @Size(max = 80) String firstName, @Size(max = 80) String lastName,
                         LocalDate dob, @Min(0) @Max(120) Integer age,
                         @NotBlank @Pattern(regexp = "male|female|other") String sex,
                         UUID consentRecordId, @Size(max = 64) String guardianRef) {
    }

    @Schema(name = "ChatPatientRef")
    record PatientRefResponse(String patientRef) {
    }

    @Schema(name = "ChatPatientComplete")
    record CompleteRequest(@Size(max = 300) String address, @Size(max = 120) @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") String email,
                           @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String preferredLanguage) {
    }

    @Schema(name = "ChatPendingCompletion")
    record Pending(String patientRef, String firstName) {
    }

    private final TenantScope tenantScope;
    private final ChatRegistrationService service;

    ChatRegistrationController(TenantScope tenantScope, ChatRegistrationService service) {
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/patients")
    @ResponseStatus(HttpStatus.CREATED)
    PatientRefResponse create(@PathVariable UUID tid, @Valid @RequestBody CreateRequest r) {
        ServiceIdentity edge = allowed(tid);
        ChatRegistrationService.Result res = tenantScope.run(tid, () -> service.create(new ChatRegistrationService.Create(r.phone(), r.phoneHash(),
                r.firstName(), r.lastName(), r.dob(), r.age(), r.sex(), r.consentRecordId(), r.guardianRef()), edge.serviceId()));
        if (res.patientRef() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "possible-duplicate", "Possible duplicate",
                    "A patient with the same phone, name and date of birth is already registered; no second record was created.",
                    Map.of("code", "POSSIBLE_DUPLICATE", "candidates", res.duplicateRefs()));
        }
        return new PatientRefResponse(res.patientRef());
    }

    @Idempotent
    @PatchMapping("/internal/v1/tenants/{tid}/patients/{patientRef}")
    PatientRefResponse complete(@PathVariable UUID tid, @PathVariable String patientRef, @Valid @RequestBody CompleteRequest r) {
        ServiceIdentity edge = allowed(tid);
        return tenantScope.run(tid, () -> {
            service.complete(patientRef, r.address(), r.email(), r.preferredLanguage(), edge.serviceId());
            return new PatientRefResponse(patientRef);
        });
    }

    /** Who on this phone the desk asked to finish in chat (404 = nobody). Reference and first name only. */
    @GetMapping("/internal/v1/tenants/{tid}/patients/pending-completion/{phoneHash}")
    Pending pending(@PathVariable UUID tid, @PathVariable @Pattern(regexp = "^[0-9a-f]{64}$") String phoneHash) {
        allowed(tid);
        return tenantScope.run(tid, () -> service.pendingCompletion(phoneHash).map(m -> new Pending(m.get("patientRef"), m.get("firstName")))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "Nobody is waiting to complete a registration on that phone.")));
    }

    private static ServiceIdentity allowed(UUID tid) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        return edge;
    }
}
