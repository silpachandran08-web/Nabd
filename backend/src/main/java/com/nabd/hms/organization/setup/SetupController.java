package com.nabd.hms.organization.setup;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.organization.setup.dto.ChargeHeadResponse;
import com.nabd.hms.organization.setup.dto.ChargeHeadWriteRequest;
import com.nabd.hms.organization.setup.dto.ClinicHolidayResponse;
import com.nabd.hms.organization.setup.dto.ClinicHolidayWriteRequest;
import com.nabd.hms.organization.setup.dto.ClinicProfileResponse;
import com.nabd.hms.organization.setup.dto.ClinicProfileWriteRequest;
import com.nabd.hms.organization.setup.dto.ConsentContactResponse;
import com.nabd.hms.organization.setup.dto.ConsentContactWriteRequest;
import com.nabd.hms.organization.setup.dto.ExportJobResponse;
import com.nabd.hms.organization.setup.dto.ImportJobRequest;
import com.nabd.hms.organization.setup.dto.ImportJobResponse;
import com.nabd.hms.organization.setup.dto.LicenceResponse;
import com.nabd.hms.organization.setup.dto.LicenceWriteRequest;
import com.nabd.hms.organization.setup.dto.PayrollExportRowResponse;
import com.nabd.hms.organization.setup.dto.PolicyResponse;
import com.nabd.hms.organization.setup.dto.PolicyWriteRequest;
import com.nabd.hms.organization.setup.dto.SetupChecklistItemResponse;
import com.nabd.hms.organization.setup.dto.StaffShiftResponse;
import com.nabd.hms.organization.setup.dto.StaffShiftWriteRequest;
import com.nabd.hms.organization.setup.dto.StaffSummaryResponse;
import com.nabd.hms.organization.setup.dto.SubscriptionSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/setup")
public class SetupController {

    private final SetupService service;

    private final com.nabd.hms.organization.dataimport.ImportPipeline importPipeline;

    SetupController(SetupService service, com.nabd.hms.organization.dataimport.ImportPipeline importPipeline) {
        this.service = service;
        this.importPipeline = importPipeline;
    }

    // ── Wizard / checklist ──

    @GetMapping("/checklist")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<SetupChecklistItemResponse> checklist(@CurrentTenant UUID tenantId) {
        return service.getChecklist(tenantId);
    }

    // status is derived from the endpoint itself, never from a request body — a call to .../skip
    // always skips and .../complete always completes, full stop.

    @PostMapping("/checklist/{step}/skip")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<Void> skipStep(@CurrentTenant UUID tenantId, @PathVariable String step) {
        service.updateProgress(tenantId, step, "skipped");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checklist/{step}/complete")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<Void> completeStep(@CurrentTenant UUID tenantId, @PathVariable String step) {
        service.updateProgress(tenantId, step, "done");
        return ResponseEntity.noContent().build();
    }

    // ── Clinic profile ──

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('setup:view')")
    public ClinicProfileResponse profile(@CurrentTenant UUID tenantId) {
        return service.getProfile(tenantId);
    }

    @PatchMapping("/profile")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ClinicProfileResponse updateProfile(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                @Valid @RequestBody ClinicProfileWriteRequest req) {
        return service.updateProfile(tenantId, staffId, req);
    }

    // ── Charge head / price master ──

    @GetMapping("/charges")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ChargeHeadResponse> charges(@CurrentTenant UUID tenantId) {
        return service.listCharges(tenantId);
    }

    @PostMapping("/charges")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ChargeHeadResponse> addCharge(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                         @Valid @RequestBody ChargeHeadWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addCharge(tenantId, staffId, req));
    }

    @PatchMapping("/charges/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ChargeHeadResponse updateCharge(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                            @Valid @RequestBody ChargeHeadWriteRequest req) {
        return service.updateCharge(tenantId, staffId, id, req);
    }

    // ── Policies ──

    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<PolicyResponse> policies(@CurrentTenant UUID tenantId) {
        return service.listPolicies(tenantId);
    }

    @PatchMapping("/policies/{key}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public PolicyResponse updatePolicy(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable String key,
                                        @Valid @RequestBody PolicyWriteRequest req) {
        return service.updatePolicy(tenantId, staffId, key, req);
    }

    // ── Consent & privacy contact ──

    @GetMapping("/consent-contact")
    @PreAuthorize("hasAuthority('setup:view')")
    public ConsentContactResponse consentContact(@CurrentTenant UUID tenantId) {
        return service.getConsentContact(tenantId);
    }

    @PatchMapping("/consent-contact")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ConsentContactResponse updateConsentContact(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                        @Valid @RequestBody ConsentContactWriteRequest req) {
        return service.updateConsentContact(tenantId, staffId, req);
    }

    // ── Clinic holidays (schedule admin) ──

    @GetMapping("/holidays")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ClinicHolidayResponse> holidays(@CurrentTenant UUID tenantId) {
        return service.listHolidays(tenantId);
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ClinicHolidayResponse> addHoliday(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                             @Valid @RequestBody ClinicHolidayWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addHoliday(tenantId, staffId, req));
    }

    @DeleteMapping("/holidays/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<Void> deleteHoliday(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        service.deleteHoliday(tenantId, staffId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Staff shifts ──

    @GetMapping("/shifts")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<StaffShiftResponse> shifts(@CurrentTenant UUID tenantId) {
        return service.listShifts(tenantId);
    }

    @PostMapping("/shifts")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<StaffShiftResponse> addShift(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                        @Valid @RequestBody StaffShiftWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addShift(tenantId, staffId, req));
    }

    // ── Payroll export ──

    @GetMapping("/payroll-export")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<PayrollExportRowResponse> payrollExport(@CurrentTenant UUID tenantId,
                                                         @RequestParam(required = false) String month) {
        return service.payrollExport(tenantId, month);
    }

    // ── Subscription ──

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('setup:view')")
    public SubscriptionSummaryResponse subscription(@CurrentTenant UUID tenantId) {
        return service.getSubscription(tenantId);
    }

    // ── Import / Export ──

    @GetMapping("/import-jobs")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ImportJobResponse> importJobs(@CurrentTenant UUID tenantId) {
        return service.listImportJobs(tenantId);
    }

    @PostMapping("/import-jobs")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ImportJobResponse> createImportJob(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                              @Valid @RequestBody ImportJobRequest req) {
        if (req.uploadId() != null) { // ADM-012: staged pipeline
            return ResponseEntity.status(HttpStatus.CREATED).body(importPipeline.create(staffId, req));
        }
        if (req.fileName() == null || req.fileName().isBlank()) {
            throw new com.nabd.hms.common.ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "fileName or uploadId is required");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addImportJob(tenantId, staffId, req));
    }

    @GetMapping("/export-jobs")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ExportJobResponse> exportJobs(@CurrentTenant UUID tenantId) {
        return service.listExportJobs(tenantId);
    }

    // ADM-015: POST /export-jobs (start a full export) and GET /export-jobs/{id} live in organization.export.DataExportController

    // ── Licence registry ──

    @GetMapping("/licences")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<LicenceResponse> licences(@CurrentTenant UUID tenantId, @RequestParam(required = false) String kind, @RequestParam(required = false) String status) {
        return service.listLicences(tenantId, kind, status);
    }

    @PostMapping("/licences")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<LicenceResponse> addLicence(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                       @Valid @RequestBody LicenceWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addLicence(tenantId, staffId, req));
    }

    @PatchMapping("/licences/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public LicenceResponse updateLicence(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                          @Valid @RequestBody LicenceWriteRequest req) {
        return service.updateLicence(tenantId, staffId, id, req);
    }

    // ── Staff lookup helper (for shift/licence forms) ──

    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<StaffSummaryResponse> staff(@CurrentTenant UUID tenantId) {
        return service.listStaff(tenantId);
    }
}
