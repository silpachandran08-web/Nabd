package com.nabd.hms.nursing;

import com.nabd.hms.nursing.dto.ActivityEntryResponse;
import com.nabd.hms.nursing.dto.AdministerRequest;
import com.nabd.hms.nursing.dto.AdministrationOrderRequest;
import com.nabd.hms.nursing.dto.AdministrationOrderResponse;
import com.nabd.hms.nursing.dto.ProcedureNotesRequest;
import com.nabd.hms.nursing.dto.ProcedureOrderRequest;
import com.nabd.hms.nursing.dto.ProcedureOrderResponse;
import com.nabd.hms.nursing.dto.ProcedureStatusRequest;
import com.nabd.hms.nursing.dto.RefuseRequest;
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

    NursingController(AdministrationService administrationService, ProcedureService procedureService, ActivityService activityService) {
        this.administrationService = administrationService;
        this.procedureService = procedureService;
        this.activityService = activityService;
    }

    @PostMapping("/administration-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('nursing:create')")
    public AdministrationOrderResponse orderAdministration(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AdministrationOrderRequest req) {
        return administrationService.order(tenantId(jwt), staffId(jwt), req);
    }

    @GetMapping("/administration-orders/today")
    @PreAuthorize("hasAuthority('nursing:view')")
    public List<AdministrationOrderResponse> administrationOrdersToday(@AuthenticationPrincipal Jwt jwt) {
        return administrationService.listToday(tenantId(jwt));
    }

    @PostMapping("/administration-orders/{id}/administer")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public AdministrationOrderResponse administer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody AdministerRequest req) {
        return administrationService.administer(tenantId(jwt), staffId(jwt), id, req);
    }

    @PostMapping("/administration-orders/{id}/refuse")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public AdministrationOrderResponse refuse(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RefuseRequest req) {
        return administrationService.refuse(tenantId(jwt), staffId(jwt), id, req);
    }

    @PostMapping("/procedure-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('nursing:create')")
    public ProcedureOrderResponse orderProcedure(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProcedureOrderRequest req) {
        return procedureService.order(tenantId(jwt), staffId(jwt), req);
    }

    @GetMapping("/procedure-orders/today")
    @PreAuthorize("hasAuthority('nursing:view')")
    public List<ProcedureOrderResponse> procedureOrdersToday(@AuthenticationPrincipal Jwt jwt) {
        return procedureService.listToday(tenantId(jwt));
    }

    @PatchMapping("/procedure-orders/{id}/status")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public ProcedureOrderResponse updateProcedureStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ProcedureStatusRequest req) {
        return procedureService.updateStatus(tenantId(jwt), staffId(jwt), id, req);
    }

    @PatchMapping("/procedure-orders/{id}/notes")
    @PreAuthorize("hasAuthority('nursing:edit')")
    public ProcedureOrderResponse updateProcedureNotes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ProcedureNotesRequest req) {
        return procedureService.updateNotes(tenantId(jwt), id, req);
    }

    @GetMapping("/activity/today")
    @PreAuthorize("hasAuthority('nursing:view')")
    public List<ActivityEntryResponse> activityToday(@AuthenticationPrincipal Jwt jwt) {
        return activityService.today(tenantId(jwt), staffId(jwt));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
