package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-024: the brand directory (find a patient of a sibling branch by phone, link with the date of birth), the patient's links
 * and opt-out, the owner's sharing profile and the clinic's directory log. The same permissions as registering a patient; the
 * limits are in BrandDirectoryService and the V3690 functions.
 */
@RestController
class BrandDirectoryController {

    /** phone and dateOfBirth are asked again here: a link needs the exact mobile and the exact date of birth, not only the reference. */
    @Schema(name = "BrandLinkRequest")
    record LinkRequest(@NotNull UUID sourceTenantId, @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sourcePatientRef,
                       @NotBlank @Size(max = 32) String phone, @NotNull @Past LocalDate dateOfBirth) {
    }

    @Schema(name = "BrandLinkResponse")
    record LinkResponse(UUID patientId) {
    }

    @Schema(name = "BrandDirectoryOptOutRequest")
    record OptOutRequest(@NotNull Boolean optOut) {
    }

    @Schema(name = "BrandSharingProfileRequest")
    record ProfileRequest(@NotBlank @Pattern(regexp = "NONE|DEMOGRAPHICS") String profile, Boolean noticeDisclosesDirectory) {
    }

    @Schema(name = "BrandDirectoryLog")
    record LogResponse(List<BrandDirectoryService.LogItem> items) {
    }

    @Schema(name = "BrandLinkUnlinkResponse")
    record UnlinkResponse(int ended) {
    }

    private final BrandDirectoryService service;
    private final TenantScope tenantScope;

    BrandDirectoryController(BrandDirectoryService service, TenantScope tenantScope) {
        this.service = service;
        this.tenantScope = tenantScope;
    }

    /** Whether the desk may search sibling branches at all (the screen asks this before it shows or requests anything). No patient data. */
    @GetMapping("/v1/patients/brand-directory/status")
    @PreAuthorize("hasAuthority('patients:create')")
    Map<String, Boolean> status(@CurrentStaff UUID staffId) {
        return Map.of("enabled", service.enabled(staffId));
    }

    /** Name, sex and year of birth only. 403 DIRECTORY_DISABLED unless the brand profile is DEMOGRAPHICS; 429 above 30 a minute per user. */
    @GetMapping("/v1/patients/brand-directory")
    @PreAuthorize("hasAuthority('patients:create')")
    BrandDirectoryService.Lookup lookup(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @RequestParam String phone,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateOfBirth,
                                        HttpServletRequest http) {
        return service.lookup(tenantId, staffId, phone, dateOfBirth, RequestMeta.clientIp(http));
    }

    @Idempotent
    @PostMapping("/v1/patients/brand-links")
    @PreAuthorize("hasAuthority('patients:create')")
    @ResponseStatus(HttpStatus.CREATED)
    LinkResponse link(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody LinkRequest req, HttpServletRequest http) {
        BrandDirectoryService.LinkResult r = service.link(tenantId, staffId, req.sourceTenantId(), req.sourcePatientRef(), req.phone(),
                req.dateOfBirth(), RequestMeta.clientIp(http));
        switch (r.outcome()) {
            case "ok":
                return new LinkResponse(r.patientId());
            case "disabled":
                throw BrandDirectoryService.disabled();
            case "opted_out":
                throw problem(HttpStatus.GONE, "SOURCE_OPTED_OUT", "This patient chose not to be found from other branches. Register them here.");
            case "dob_mismatch":
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DOB_MISMATCH",
                        "The date of birth does not match the record at the other branch. Check the patient's identity in person.");
            case "already_linked":
                throw problem(HttpStatus.CONFLICT, "ALREADY_LINKED", "This patient is already linked to that branch.");
            default:
                throw ApiException.notFound();
        }
    }

    @GetMapping("/v1/patients/{patientId}/brand-links")
    @PreAuthorize("hasAuthority('patients:view')")
    BrandDirectoryService.PatientLinks links(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID patientId) {
        return service.links(tenantId, staffId, patientId);
    }

    /** patients:edit and, in the service, the built-in Owner role. */
    @DeleteMapping("/v1/patients/{patientId}/brand-links/{linkId}")
    @PreAuthorize("hasAuthority('patients:edit')")
    ResponseEntity<Void> unlink(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID patientId, @PathVariable UUID linkId,
                                HttpServletRequest http) {
        service.unlink(tenantId, staffId, patientId, linkId, RequestMeta.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/v1/patients/{patientId}/brand-directory-opt-out")
    @PreAuthorize("hasAuthority('patients:edit')")
    ResponseEntity<Void> optOut(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID patientId,
                                @Valid @RequestBody OptOutRequest req) {
        service.setOptOut(tenantId, staffId, patientId, req.optOut());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/settings/privacy/brand-directory")
    @PreAuthorize("hasAuthority('setup:view')")
    BrandDirectoryService.Settings settings(@CurrentStaff UUID staffId) {
        return service.settings(staffId);
    }

    /** setup:edit and, in the database function, the brand's owner account on the built-in Owner role. */
    @PutMapping("/v1/settings/privacy/brand-directory")
    @PreAuthorize("hasAuthority('setup:edit')")
    BrandDirectoryService.Settings setProfile(@CurrentStaff UUID staffId, @Valid @RequestBody ProfileRequest req, HttpServletRequest http) {
        return service.setProfile(staffId, req.profile(), Boolean.TRUE.equals(req.noticeDisclosesDirectory()), RequestMeta.clientIp(http));
    }

    /** The owner's Compliance and audit screen: lookups made from this clinic and lookups of its patients by sibling branches. */
    @GetMapping("/v1/patients/brand-directory/lookups")
    @PreAuthorize("hasAuthority('compliance:view')")
    LogResponse lookups(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return new LogResponse(service.lookups(tenantId, staffId));
    }

    /**
     * For the Patient Access Edge: the patient, on their verified phone, ends every link of their record. The chat flow that
     * calls this is not built yet (PLT-024 report).
     */
    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/patients/{patientRef}/brand-links/unlink")
    UnlinkResponse unlinkForPatient(@PathVariable UUID tid, @PathVariable String patientRef) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        return tenantScope.run(tid, () -> new UnlinkResponse(service.unlinkForPatient(tid, patientRef, edge.serviceId())));
    }

    private static ApiException problem(HttpStatus status, String code, String detail) {
        return new ApiException(status, code, code, detail, Map.of("code", code));
    }
}
