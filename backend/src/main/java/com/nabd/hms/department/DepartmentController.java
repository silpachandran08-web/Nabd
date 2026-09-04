package com.nabd.hms.department;

import com.nabd.hms.department.dto.DepartmentResponse;
import com.nabd.hms.department.dto.DepartmentWorkflowRequest;
import com.nabd.hms.department.dto.DepartmentWorkflowResponse;
import com.nabd.hms.department.dto.DepartmentWriteRequest;
import com.nabd.hms.department.dto.FlowStepResponse;
import com.nabd.hms.department.dto.TransferEdge;
import com.nabd.hms.department.dto.TransferGraphRequest;
import com.nabd.hms.department.dto.TransferTargetResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/departments")
public class DepartmentController {

    private final DepartmentService service;

    DepartmentController(DepartmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('departments:view')")
    public List<DepartmentResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(tenantId(jwt));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('departments:create')")
    public ResponseEntity<DepartmentResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DepartmentWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(tenantId(jwt), staffId(jwt), req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('departments:edit')")
    public DepartmentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @Valid @RequestBody DepartmentWriteRequest req) {
        return service.update(tenantId(jwt), staffId(jwt), id, req);
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasAuthority('departments:view')")
    public List<TransferEdge> listTransfers(@AuthenticationPrincipal Jwt jwt) {
        return service.listTransfers(tenantId(jwt));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('departments:edit')")
    public List<TransferEdge> replaceTransfers(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TransferGraphRequest req) {
        return service.replaceTransfers(tenantId(jwt), staffId(jwt), req);
    }

    /** Narrower authority than the rest of this controller — doctors/front-desk driving the
     * consult-page transfer picker need this without full departments:view, same "queue:view is
     * enough for a roster read" precedent as StaffController's /staff/roster. */
    @GetMapping("/{id}/transfer-targets")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<TransferTargetResponse> transferTargets(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.transferTargets(tenantId(jwt), id);
    }

    /** queue:view, not departments:view — front-desk/nursing/billing staff on the checkout and
     * nursing pages need to read a department's flow to know what "next" means at their current
     * stop, same broader-access precedent as transfer-targets above. */
    @GetMapping("/{id}/flow")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<FlowStepResponse> listFlow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listFlow(tenantId(jwt), id);
    }

    /** The platform-authored template a department is running on, plus the toggle values it set
     * and the full template library — what the owner's editor renders its picker from. */
    @GetMapping("/{id}/workflow")
    @PreAuthorize("hasAuthority('departments:view')")
    public DepartmentWorkflowResponse getWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.getWorkflow(tenantId(jwt), id);
    }

    @PostMapping("/{id}/workflow")
    @PreAuthorize("hasAuthority('departments:edit')")
    public DepartmentWorkflowResponse replaceWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                        @Valid @RequestBody DepartmentWorkflowRequest req) {
        return service.replaceWorkflow(tenantId(jwt), staffId(jwt), id, req);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
