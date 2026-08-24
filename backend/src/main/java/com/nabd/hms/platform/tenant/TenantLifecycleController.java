package com.nabd.hms.platform.tenant;

import com.nabd.hms.platform.tenant.dto.TenantLifecycleResponse;
import com.nabd.hms.platform.tenant.dto.TransitionRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Gated on tenant_detail:view — per NB-257's matrix, super_admin/implementation/support_engineer/compliance_dpo. */
@RestController
@RequestMapping("/v1/platform/tenants/{tenantId}/lifecycle")
@PreAuthorize("hasAuthority('tenant_detail:view')")
public class TenantLifecycleController {

    private final TenantLifecycleService service;

    TenantLifecycleController(TenantLifecycleService service) {
        this.service = service;
    }

    @GetMapping
    public TenantLifecycleResponse get(@PathVariable UUID tenantId) {
        return service.getLifecycle(tenantId);
    }

    @PostMapping("/transitions")
    public TenantLifecycleResponse transition(@PathVariable UUID tenantId, @Valid @RequestBody TransitionRequest req,
                                                @AuthenticationPrincipal Jwt jwt) {
        return service.transition(tenantId, req.toStatus(), UUID.fromString(jwt.getSubject()), req.reason());
    }
}
