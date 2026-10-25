package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.PageRequest;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.messaging.api.MessageHistory;
import com.nabd.hms.staff.StaffService;
import com.nabd.hms.staff.dto.CallerInfo;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** COM-006: GET /v1/patients/{patientId}/messages. Same verification and own-patients gates as the patient file itself. */
@RestController
public class PatientMessagesController {

    private final PatientRepository repo;
    private final StaffService staffService;
    private final TenantContext tenantContext;
    private final MessageHistory history;

    PatientMessagesController(PatientRepository repo, StaffService staffService, TenantContext tenantContext, MessageHistory history) {
        this.repo = repo;
        this.staffService = staffService;
        this.tenantContext = tenantContext;
        this.history = history;
    }

    @Transactional(readOnly = true)
    @com.nabd.hms.shared.phiread.PhiRead("patient_messages_list")
    @GetMapping("/v1/patients/{patientId}/messages")
    @PreAuthorize("hasAuthority('patients:view')")
    public MessageHistory.Page messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                        @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        int pageLimit = limit == null ? 50 : PageRequest.limit(limit, 50);
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenantId"));
        UUID staffId = UUID.fromString(jwt.getSubject());
        CallerInfo caller = staffService.getCallerInfo(tenantId, staffId);
        if (!caller.emailVerified() || !caller.mobileVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "verification-required", "Verification required",
                    "Both email and mobile verification must be complete before patient data is reachable.");
        }
        tenantContext.set(tenantId);
        UUID scope = "own_patients_only".equals(caller.scope()) ? staffId : null;
        repo.findVisibleById(tenantId, patientId, scope)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        return history.page(patientId, cursor, pageLimit);
    }
}
