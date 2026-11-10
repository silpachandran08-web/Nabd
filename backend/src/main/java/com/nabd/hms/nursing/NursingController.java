package com.nabd.hms.nursing;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.clinical.ClinicalReadScope;
import com.nabd.hms.nursing.dto.ActivityEntryResponse;
import com.nabd.hms.nursing.dto.AdministerRequest;
import com.nabd.hms.nursing.dto.AdministrationOrderRequest;
import com.nabd.hms.nursing.dto.AdministrationOrderResponse;
import com.nabd.hms.nursing.dto.ProcedureConsentRequest;
import com.nabd.hms.nursing.dto.ProcedureNotesRequest;
import com.nabd.hms.nursing.dto.ProcedureOrderRequest;
import com.nabd.hms.nursing.dto.ProcedureOrderResponse;
import com.nabd.hms.nursing.dto.ProcedureStatusRequest;
import com.nabd.hms.nursing.dto.RefuseRequest;
import com.nabd.hms.nursing.dto.VaccineRecordRequest;
import com.nabd.hms.nursing.dto.VaccineRecordResponse;
import com.nabd.hms.nursing.dto.WorklistResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/nursing")
public class NursingController {

    private final AdministrationService administrationService;
    private final ProcedureService procedureService;
    private final ActivityService activityService;
    private final VaccineRecordService vaccineRecordService;
    private final WorklistService worklistService;

    private final ClinicalReadScope scopes;

    NursingController(AdministrationService administrationService, ProcedureService procedureService, ActivityService activityService,
                      VaccineRecordService vaccineRecordService, WorklistService worklistService, ClinicalReadScope scopes) {
        this.scopes = scopes;
        this.worklistService = worklistService;
        this.vaccineRecordService = vaccineRecordService;
        this.administrationService = administrationService;
        this.procedureService = procedureService;
        this.activityService = activityService;
    }

    @PostMapping("/administration-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('nursing:create')")
    public AdministrationOrderResponse orderAdministration(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody AdministrationOrderRequest req) {
        return administrationService.order(tenantId, staffId, req);
    }

    @GetMapping("/administration-orders/today")
    @PreAuthorize("hasAuthority('nursing:view')")
    public List<AdministrationOrderResponse> administrationOrdersToday(@CurrentTenant UUID tenantId) {
        return administrationService.listToday(tenantId);
    }

    @PostMapping("/administration-orders/{id}/administer")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public AdministrationOrderResponse administer(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody AdministerRequest req) {
        return administrationService.administer(tenantId, staffId, id, req);
    }

    @PostMapping("/administration-orders/{id}/refuse")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public AdministrationOrderResponse refuse(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody RefuseRequest req) {
        return administrationService.refuse(tenantId, staffId, id, req);
    }

    @PostMapping("/procedure-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('nursing:create')")
    public ProcedureOrderResponse orderProcedure(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody ProcedureOrderRequest req) {
        return procedureService.order(tenantId, staffId, req);
    }

    @GetMapping("/procedure-orders/today")
    @PreAuthorize("hasAuthority('nursing:view')")
    public List<ProcedureOrderResponse> procedureOrdersToday(@CurrentTenant UUID tenantId) {
        return procedureService.listToday(tenantId);
    }

    @PatchMapping("/procedure-orders/{id}/status")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public ProcedureOrderResponse updateProcedureStatus(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody ProcedureStatusRequest req) {
        return procedureService.updateStatus(tenantId, staffId, id, req);
    }

    @PostMapping("/procedure-orders/{id}/consent")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public ProcedureOrderResponse recordProcedureConsent(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody ProcedureConsentRequest req) {
        return procedureService.recordConsent(tenantId, staffId, id, req);
    }

    @PatchMapping("/procedure-orders/{id}/notes")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public ProcedureOrderResponse updateProcedureNotes(@CurrentTenant UUID tenantId, @PathVariable UUID id, @Valid @RequestBody ProcedureNotesRequest req) {
        return procedureService.updateNotes(tenantId, id, req);
    }

    @GetMapping("/activity/today")
    @PreAuthorize("hasAuthority('nursing:view')")
    public List<ActivityEntryResponse> activityToday(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return activityService.today(tenantId, staffId);
    }

    @PostMapping("/vaccine-records")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('nursing:create')")
    public VaccineRecordResponse recordVaccine(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody VaccineRecordRequest req) {
        return vaccineRecordService.record(tenantId, staffId, req);
    }

    @org.springframework.transaction.annotation.Transactional // IDC-001: caller lookup, gate and read share one pool connection
    @GetMapping("/vaccine-records/today")
    @PreAuthorize("hasAuthority('nursing:view')")
    public List<VaccineRecordResponse> vaccineRecordsToday(@AuthenticationPrincipal Jwt jwt) {
        return vaccineRecordService.listToday(scopes.resolve(jwt)); // IDC-001: consent + own-patients gate in the list SQL
    }

    /** NUR-019: same permission as GET /queue; patient/vitals sub-objects follow patients:view / clinical:view. */
    @GetMapping("/worklist")
    @PreAuthorize("hasAuthority('queue:view')")
    public WorklistResponse worklist(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @AuthenticationPrincipal Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return worklistService.worklist(tenantId, staffId,
                permissions != null && permissions.contains("patients:view"),
                permissions != null && permissions.contains("clinical:view"));
    }
}
