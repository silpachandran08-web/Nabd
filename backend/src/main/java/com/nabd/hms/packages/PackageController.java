package com.nabd.hms.packages;

import com.nabd.hms.packages.dto.ExpiringSoonResponse;
import com.nabd.hms.packages.dto.ExtendRequest;
import com.nabd.hms.packages.dto.InstanceResponse;
import com.nabd.hms.packages.dto.LiabilityResponse;
import com.nabd.hms.packages.dto.PackageResponse;
import com.nabd.hms.packages.dto.PackageSettingsResponse;
import com.nabd.hms.packages.dto.PackageSettingsWriteRequest;
import com.nabd.hms.packages.dto.PackageWriteRequest;
import com.nabd.hms.packages.dto.RefundPreviewResponse;
import com.nabd.hms.packages.dto.RefundRequestRequest;
import com.nabd.hms.packages.dto.RefundResponse;
import com.nabd.hms.packages.dto.SellPackageRequest;
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
@RequestMapping("/v1/packages")
public class PackageController {

    private final PackageCatalogueService catalogueService;
    private final PackageInstanceService instanceService;

    PackageController(PackageCatalogueService catalogueService, PackageInstanceService instanceService) {
        this.catalogueService = catalogueService;
        this.instanceService = instanceService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('packages:view')")
    public List<PackageResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return catalogueService.list(tenantId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('packages:create')")
    public PackageResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PackageWriteRequest req) {
        return catalogueService.create(tenantId(jwt), staffId(jwt), req);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('packages:view')")
    public PackageResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return catalogueService.get(tenantId(jwt), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PackageWriteRequest req) {
        return catalogueService.update(tenantId(jwt), id, req);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return catalogueService.activate(tenantId(jwt), id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageResponse deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return catalogueService.deactivate(tenantId(jwt), id);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('packages:view')")
    public PackageSettingsResponse getSettings(@AuthenticationPrincipal Jwt jwt) {
        return catalogueService.getSettings(tenantId(jwt));
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageSettingsResponse updateSettings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PackageSettingsWriteRequest req) {
        return catalogueService.updateSettings(tenantId(jwt), req);
    }

    @PostMapping("/sell")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('packages:create')")
    public InstanceResponse sell(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SellPackageRequest req) {
        return instanceService.sell(tenantId(jwt), staffId(jwt), req);
    }

    @GetMapping("/instances")
    @PreAuthorize("hasAuthority('packages:view')")
    public List<InstanceResponse> listInstances(@AuthenticationPrincipal Jwt jwt) {
        return instanceService.list(tenantId(jwt));
    }

    @GetMapping("/instances/{id}")
    @PreAuthorize("hasAuthority('packages:view')")
    public InstanceResponse getInstance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return instanceService.detail(tenantId(jwt), id);
    }

    @PostMapping("/instances/items/{itemId}/book")
    @PreAuthorize("hasAuthority('packages:edit')")
    public InstanceResponse book(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId) {
        return instanceService.book(tenantId(jwt), staffId(jwt), itemId);
    }

    @PostMapping("/instances/items/{itemId}/redeem")
    @PreAuthorize("hasAuthority('packages:edit')")
    public InstanceResponse redeem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId) {
        return instanceService.redeem(tenantId(jwt), staffId(jwt), itemId);
    }

    @PostMapping("/instances/{id}/extend")
    @PreAuthorize("hasAuthority('packages:approve')")
    public InstanceResponse extend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ExtendRequest req) {
        return instanceService.extend(tenantId(jwt), staffId(jwt), id, req);
    }

    @GetMapping("/expiring-soon")
    @PreAuthorize("hasAuthority('packages:view')")
    public List<ExpiringSoonResponse> expiringSoon(@AuthenticationPrincipal Jwt jwt) {
        return instanceService.expiringSoon(tenantId(jwt));
    }

    @PostMapping("/instances/{id}/send-reminder")
    @PreAuthorize("hasAuthority('packages:edit')")
    public InstanceResponse sendReminder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return instanceService.sendReminder(tenantId(jwt), staffId(jwt), id);
    }

    @GetMapping("/instances/{id}/refund-preview")
    @PreAuthorize("hasAuthority('packages:view')")
    public RefundPreviewResponse refundPreview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return instanceService.refundPreview(tenantId(jwt), id);
    }

    @PostMapping("/instances/{id}/refund")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('packages:edit')")
    public RefundResponse requestRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RefundRequestRequest req) {
        return instanceService.requestRefund(tenantId(jwt), staffId(jwt), id, req);
    }

    @GetMapping("/refunds")
    @PreAuthorize("hasAuthority('packages:view')")
    public List<RefundResponse> listRefunds(@AuthenticationPrincipal Jwt jwt) {
        return instanceService.listRefunds(tenantId(jwt));
    }

    @PostMapping("/refunds/{id}/approve")
    @PreAuthorize("hasAuthority('packages:approve')")
    public RefundResponse approveRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return instanceService.approveRefund(tenantId(jwt), staffId(jwt), id);
    }

    @GetMapping("/liability")
    @PreAuthorize("hasAuthority('packages:view')")
    public LiabilityResponse liability(@AuthenticationPrincipal Jwt jwt) {
        return instanceService.liability(tenantId(jwt));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
