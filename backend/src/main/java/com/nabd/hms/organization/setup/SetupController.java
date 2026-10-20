package com.nabd.hms.setup;

import com.nabd.hms.setup.dto.ChargeHeadResponse;
import com.nabd.hms.setup.dto.ChargeHeadWriteRequest;
import com.nabd.hms.setup.dto.ClinicHolidayResponse;
import com.nabd.hms.setup.dto.ClinicHolidayWriteRequest;
import com.nabd.hms.setup.dto.ClinicProfileResponse;
import com.nabd.hms.setup.dto.ClinicProfileWriteRequest;
import com.nabd.hms.setup.dto.ConsentContactResponse;
import com.nabd.hms.setup.dto.ConsentContactWriteRequest;
import com.nabd.hms.setup.dto.ExportJobRequest;
import com.nabd.hms.setup.dto.ExportJobResponse;
import com.nabd.hms.setup.dto.ImportJobRequest;
import com.nabd.hms.setup.dto.ImportJobResponse;
import com.nabd.hms.setup.dto.LicenceResponse;
import com.nabd.hms.setup.dto.LicenceWriteRequest;
import com.nabd.hms.setup.dto.PayrollExportRowResponse;
import com.nabd.hms.setup.dto.PolicyResponse;
import com.nabd.hms.setup.dto.PolicyWriteRequest;
import com.nabd.hms.setup.dto.SetupChecklistItemResponse;
import com.nabd.hms.setup.dto.StaffShiftResponse;
import com.nabd.hms.setup.dto.StaffShiftWriteRequest;
import com.nabd.hms.setup.dto.StaffSummaryResponse;
import com.nabd.hms.setup.dto.SubscriptionSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    SetupController(SetupService service) {
        this.service = service;
    }

    // ── Wizard / checklist ──

    @GetMapping("/checklist")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<SetupChecklistItemResponse> checklist(@AuthenticationPrincipal Jwt jwt) {
        return service.getChecklist(tenantId(jwt));
    }

    // status is derived from the endpoint itself, never from a request body — a call to .../skip
    // always skips and .../complete always completes, full stop.

    @PostMapping("/checklist/{step}/skip")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<Void> skipStep(@AuthenticationPrincipal Jwt jwt, @PathVariable String step) {
        service.updateProgress(tenantId(jwt), step, "skipped");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checklist/{step}/complete")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<Void> completeStep(@AuthenticationPrincipal Jwt jwt, @PathVariable String step) {
        service.updateProgress(tenantId(jwt), step, "done");
        return ResponseEntity.noContent().build();
    }

    // ── Clinic profile ──

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('setup:view')")
    public ClinicProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        return service.getProfile(tenantId(jwt));
    }

    @PatchMapping("/profile")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ClinicProfileResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody ClinicProfileWriteRequest req) {
        return service.updateProfile(tenantId(jwt), staffId(jwt), req);
    }

    // ── Charge head / price master ──

    @GetMapping("/charges")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ChargeHeadResponse> charges(@AuthenticationPrincipal Jwt jwt) {
        return service.listCharges(tenantId(jwt));
    }

    @PostMapping("/charges")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ChargeHeadResponse> addCharge(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody ChargeHeadWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addCharge(tenantId(jwt), staffId(jwt), req));
    }

    @PatchMapping("/charges/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ChargeHeadResponse updateCharge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody ChargeHeadWriteRequest req) {
        return service.updateCharge(tenantId(jwt), staffId(jwt), id, req);
    }

    // ── Policies ──

    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<PolicyResponse> policies(@AuthenticationPrincipal Jwt jwt) {
        return service.listPolicies(tenantId(jwt));
    }

    @PatchMapping("/policies/{key}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public PolicyResponse updatePolicy(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                        @Valid @RequestBody PolicyWriteRequest req) {
        return service.updatePolicy(tenantId(jwt), staffId(jwt), key, req);
    }

    // ── Consent & privacy contact ──

    @GetMapping("/consent-contact")
    @PreAuthorize("hasAuthority('setup:view')")
    public ConsentContactResponse consentContact(@AuthenticationPrincipal Jwt jwt) {
        return service.getConsentContact(tenantId(jwt));
    }

    @PatchMapping("/consent-contact")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ConsentContactResponse updateConsentContact(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody ConsentContactWriteRequest req) {
        return service.updateConsentContact(tenantId(jwt), staffId(jwt), req);
    }

    // ── Clinic holidays (schedule admin) ──

    @GetMapping("/holidays")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ClinicHolidayResponse> holidays(@AuthenticationPrincipal Jwt jwt) {
        return service.listHolidays(tenantId(jwt));
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ClinicHolidayResponse> addHoliday(@AuthenticationPrincipal Jwt jwt,
                                                             @Valid @RequestBody ClinicHolidayWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addHoliday(tenantId(jwt), staffId(jwt), req));
    }

    @DeleteMapping("/holidays/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<Void> deleteHoliday(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteHoliday(tenantId(jwt), staffId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // ── Staff shifts ──

    @GetMapping("/shifts")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<StaffShiftResponse> shifts(@AuthenticationPrincipal Jwt jwt) {
        return service.listShifts(tenantId(jwt));
    }

    @PostMapping("/shifts")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<StaffShiftResponse> addShift(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody StaffShiftWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addShift(tenantId(jwt), staffId(jwt), req));
    }

    // ── Payroll export ──

    @GetMapping("/payroll-export")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<PayrollExportRowResponse> payrollExport(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestParam(required = false) String month) {
        return service.payrollExport(tenantId(jwt), month);
    }

    // ── Subscription ──

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('setup:view')")
    public SubscriptionSummaryResponse subscription(@AuthenticationPrincipal Jwt jwt) {
        return service.getSubscription(tenantId(jwt));
    }

    // ── Import / Export ──

    @GetMapping("/import-jobs")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ImportJobResponse> importJobs(@AuthenticationPrincipal Jwt jwt) {
        return service.listImportJobs(tenantId(jwt));
    }

    @PostMapping("/import-jobs")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ImportJobResponse> createImportJob(@AuthenticationPrincipal Jwt jwt,
                                                              @Valid @RequestBody ImportJobRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addImportJob(tenantId(jwt), staffId(jwt), req));
    }

    @GetMapping("/export-jobs")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<ExportJobResponse> exportJobs(@AuthenticationPrincipal Jwt jwt) {
        return service.listExportJobs(tenantId(jwt));
    }

    @PostMapping("/export-jobs")
    @PreAuthorize("hasAuthority('setup:view')")
    public ResponseEntity<ExportJobResponse> createExportJob(@AuthenticationPrincipal Jwt jwt,
                                                              @Valid @RequestBody ExportJobRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addExportJob(tenantId(jwt), staffId(jwt), req));
    }

    // ── Licence registry ──

    @GetMapping("/licences")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<LicenceResponse> licences(@AuthenticationPrincipal Jwt jwt) {
        return service.listLicences(tenantId(jwt));
    }

    @PostMapping("/licences")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<LicenceResponse> addLicence(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody LicenceWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addLicence(tenantId(jwt), staffId(jwt), req));
    }

    @PatchMapping("/licences/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public LicenceResponse updateLicence(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody LicenceWriteRequest req) {
        return service.updateLicence(tenantId(jwt), staffId(jwt), id, req);
    }

    // ── Staff lookup helper (for shift/licence forms) ──

    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<StaffSummaryResponse> staff(@AuthenticationPrincipal Jwt jwt) {
        return service.listStaff(tenantId(jwt));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
