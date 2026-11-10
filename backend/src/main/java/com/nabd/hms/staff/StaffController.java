package com.nabd.hms.staff;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.auth.AuthService;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.staff.dto.AcceptInviteRequest;
import com.nabd.hms.staff.dto.AcceptedStaffIdentity;
import com.nabd.hms.staff.dto.StaffInviteRequest;
import com.nabd.hms.staff.dto.StaffInviteResponse;
import com.nabd.hms.staff.dto.StaffPage;
import com.nabd.hms.staff.dto.StaffPatchRequest;
import com.nabd.hms.staff.dto.StaffResponse;
import com.nabd.hms.staff.dto.StaffRosterEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/staff")
public class StaffController {

    private final StaffService staffService;
    private final AuthService authService;

    StaffController(StaffService staffService, AuthService authService) {
        this.staffService = staffService;
        this.authService = authService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:view')")
    public StaffPage list(@CurrentTenant UUID tenantId,
                           @RequestParam(required = false) Integer limit,
                           @RequestParam(required = false) String cursor) {
        return staffService.list(tenantId, com.nabd.hms.common.PageRequest.limit(limit), cursor);
    }

    /** Queue/consult doctor pickers need id+name only — gated on queue:view (not staff:view) so
     * Reception/Nursing roles can populate them without the full HR-data staff directory. */
    @GetMapping("/roster")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<StaffRosterEntry> roster(@CurrentTenant UUID tenantId) {
        return staffService.roster(tenantId);
    }

    /** PLT-016: staff the clinician backfill could not decide; the owner confirms each with PATCH clinician. */
    @GetMapping("/clinician-review")
    @PreAuthorize("hasAuthority('staff:view')")
    public List<com.nabd.hms.staff.dto.ClinicianReviewEntry> clinicianReview(@CurrentTenant UUID tenantId) {
        return staffService.clinicianReview(tenantId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('staff:create')")
    public ResponseEntity<StaffInviteResponse> invite(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                        @Valid @RequestBody StaffInviteRequest req) {
        StaffInviteResponse created = staffService.invite(tenantId, staffId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:view')")
    public StaffResponse get(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return staffService.get(tenantId, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:edit')")
    public StaffResponse patch(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                @Valid @RequestBody StaffPatchRequest req, HttpServletRequest http) {
        return staffService.patch(tenantId, staffId, id, req, RequestMeta.clientIp(http));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('staff:delete')")
    public ResponseEntity<Void> suspend(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                         @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        staffService.suspend(tenantId, id, staffId, stepUpToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/mfa/reset")
    @PreAuthorize("hasAuthority('staff:edit')")
    public ResponseEntity<Void> resetMfa(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                          @Valid @RequestBody com.nabd.hms.staff.dto.MfaResetRequest req,
                                          @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
                                          HttpServletRequest http) {
        staffService.resetMfa(tenantId, id, staffId, stepUpToken, req, RequestMeta.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/{token}/accept")
    public Object acceptInvite(@PathVariable String token, @Valid @RequestBody AcceptInviteRequest req,
                               HttpServletRequest http) {
        AcceptedStaffIdentity identity = staffService.acceptInvite(token, req);
        return authService.sessionAfterInviteAccept(identity.tenantId(), identity.staffId(),
                RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }
}
