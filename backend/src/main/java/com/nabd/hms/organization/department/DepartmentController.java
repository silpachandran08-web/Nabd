package com.nabd.hms.organization.department;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.organization.department.dto.DepartmentResponse;
import com.nabd.hms.organization.department.dto.DepartmentWorkflowRequest;
import com.nabd.hms.organization.department.dto.DepartmentWorkflowResponse;
import com.nabd.hms.organization.department.dto.DepartmentWriteRequest;
import com.nabd.hms.organization.department.dto.FlowStepResponse;
import com.nabd.hms.organization.department.dto.TransferEdge;
import com.nabd.hms.organization.department.dto.TransferGraphRequest;
import com.nabd.hms.organization.department.dto.TransferTargetResponse;
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
    public List<DepartmentResponse> list(@CurrentTenant UUID tenantId) {
        return service.list(tenantId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('departments:create')")
    public ResponseEntity<DepartmentResponse> create(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody DepartmentWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(tenantId, staffId, req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('departments:edit')")
    public DepartmentResponse update(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                      @Valid @RequestBody DepartmentWriteRequest req) {
        return service.update(tenantId, staffId, id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('departments:delete')")
    public ResponseEntity<Void> delete(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        service.delete(tenantId, staffId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasAuthority('departments:view')")
    public List<TransferEdge> listTransfers(@CurrentTenant UUID tenantId) {
        return service.listTransfers(tenantId);
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('departments:edit')")
    public List<TransferEdge> replaceTransfers(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody TransferGraphRequest req) {
        return service.replaceTransfers(tenantId, staffId, req);
    }

    /** Narrower authority than the rest of this controller — doctors/front-desk driving the
     * consult-page transfer picker need this without full departments:view, same "queue:view is
     * enough for a roster read" precedent as StaffController's /staff/roster. */
    @GetMapping("/{id}/transfer-targets")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<TransferTargetResponse> transferTargets(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return service.transferTargets(tenantId, id);
    }

    /** queue:view, not departments:view — front-desk/nursing/billing staff on the checkout and
     * nursing pages need to read a department's flow to know what "next" means at their current
     * stop, same broader-access precedent as transfer-targets above. */
    @GetMapping("/{id}/flow")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<FlowStepResponse> listFlow(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return service.listFlow(tenantId, id);
    }

    /** The platform-authored template a department is running on, plus the toggle values it set
     * and the full template library — what the owner's editor renders its picker from. */
    @GetMapping("/{id}/workflow")
    @PreAuthorize("hasAuthority('departments:view')")
    public DepartmentWorkflowResponse getWorkflow(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return service.getWorkflow(tenantId, id);
    }

    @PostMapping("/{id}/workflow")
    @PreAuthorize("hasAuthority('departments:edit')")
    public DepartmentWorkflowResponse replaceWorkflow(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                                        @Valid @RequestBody DepartmentWorkflowRequest req) {
        return service.replaceWorkflow(tenantId, staffId, id, req);
    }
}
