package com.nabd.hms.staff;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.staff.dto.DelegationRequest;
import com.nabd.hms.staff.dto.DelegationResponse;
import com.nabd.hms.staff.dto.RoleResponse;
import com.nabd.hms.staff.dto.RoleWriteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/roles")
public class RoleController {

    private final RoleService service;

    RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:view')")
    public List<RoleResponse> list(@CurrentTenant UUID tenantId, @RequestParam(required = false) Integer limit) {
        // shortcut: PLT-027 cap applied after the read (a tenant has tens of roles); the bare list shape the shell relies on stays, default is the max
        int cap = limit == null ? com.nabd.hms.common.PageRequest.MAX : com.nabd.hms.common.PageRequest.limit(limit);
        return service.list(tenantId).stream().limit(cap).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('staff:create')")
    public ResponseEntity<RoleResponse> create(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody RoleWriteRequest req) {
        RoleResponse created = service.create(tenantId, staffId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:view')")
    public RoleResponse get(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:edit')")
    public RoleResponse update(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                @Valid @RequestBody RoleWriteRequest req) {
        return service.update(tenantId, staffId, id, req);
    }

    /** PLT-016: Owner only (checked in RoleService); 409 NOT_A_PRESET for a custom role. */
    @PostMapping("/{id}/reset-to-preset")
    @PreAuthorize("hasAuthority('staff:edit')")
    @com.nabd.hms.shared.Idempotent
    public RoleResponse resetToPreset(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        return service.resetToPreset(tenantId, staffId, id);
    }

    @PostMapping("/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('staff:edit')")
    public DelegationResponse createDelegation(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody DelegationRequest req,
                                               @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        return service.createDelegation(tenantId, staffId, req, stepUpToken);
    }

    @GetMapping("/delegations")
    @PreAuthorize("hasAuthority('staff:view')")
    public List<DelegationResponse> listDelegations(@CurrentTenant UUID tenantId) {
        return service.listDelegations(tenantId);
    }

    @DeleteMapping("/delegations/{id}")
    @PreAuthorize("hasAuthority('staff:edit')")
    public ResponseEntity<Void> revokeDelegation(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        service.revokeDelegation(tenantId, staffId, id);
        return ResponseEntity.noContent().build();
    }
}
