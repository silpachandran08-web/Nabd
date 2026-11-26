package com.nabd.hms.lab;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.lab.LabCollectionDtos.CollectionRequest;
import com.nabd.hms.lab.LabCollectionDtos.CollectionResponse;
import com.nabd.hms.shared.Idempotent;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * PHL-045: sample collection. All endpoints require the clinic's `lab` entitlement (LabEntitlementGuard) and lab:collect permission.
 */
@RestController
public class LabCollectionController {

    private final LabCollectionService service;
    private final TenantContext tenantContext;

    LabCollectionController(LabCollectionService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    /** Collect samples for an order. 404 order not found, 409 already-collected. */
    @Idempotent
    @PostMapping("/v1/lab/orders/{id}/collections")
    @PreAuthorize("hasAuthority('lab:collect')")
    public ResponseEntity<CollectionResponse> collect(@AuthenticationPrincipal Jwt jwt, @CurrentStaff UUID staffId,
                                                      @PathVariable UUID id, @Valid @RequestBody CollectionRequest req) {
        CollectionResponse resp = service.collect(id, staffId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /** Get a sample label as PDF. 404 sample not found. */
    @GetMapping("/v1/lab/samples/{accession}/label")
    @PreAuthorize("hasAuthority('lab:collect')")
    public ResponseEntity<byte[]> label(@AuthenticationPrincipal Jwt jwt, @PathVariable String accession) {
        UUID tenantId = tenantContext.current();
        byte[] pdf = service.sampleLabel(tenantId, accession);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline; filename=\"sample-label.pdf\"").body(pdf);
    }
}
