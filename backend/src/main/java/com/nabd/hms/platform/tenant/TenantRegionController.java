package com.nabd.hms.platform.tenant;

import com.nabd.hms.platform.tenant.dto.RegionChangeRequest;
import com.nabd.hms.platform.tenant.dto.TenantRegionResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** OPS-061: an operator asking to change a tenant's region always gets 409 REGION_IMMUTABLE (same value: 200, no change). */
@RestController
@RequestMapping("/v1/platform/tenants/{tenantId}/region")
@PreAuthorize("hasAuthority('tenant_detail:edit')")
public class TenantRegionController {

    private final TenantRegionService service;

    TenantRegionController(TenantRegionService service) {
        this.service = service;
    }

    @PatchMapping
    public TenantRegionResponse change(@PathVariable UUID tenantId, @Valid @RequestBody RegionChangeRequest req) {
        return service.change(tenantId, req.regionCode());
    }
}
