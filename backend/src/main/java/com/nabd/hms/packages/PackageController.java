package com.nabd.hms.packages;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public List<PackageResponse> list(@CurrentTenant UUID tenantId) {
        return catalogueService.list(tenantId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('packages:create')")
    public PackageResponse create(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody PackageWriteRequest req) {
        return catalogueService.create(tenantId, staffId, req);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('packages:view')")
    public PackageResponse get(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return catalogueService.get(tenantId, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageResponse update(@CurrentTenant UUID tenantId, @PathVariable UUID id, @Valid @RequestBody PackageWriteRequest req) {
        return catalogueService.update(tenantId, id, req);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageResponse activate(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return catalogueService.activate(tenantId, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageResponse deactivate(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return catalogueService.deactivate(tenantId, id);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('packages:view')")
    public PackageSettingsResponse getSettings(@CurrentTenant UUID tenantId) {
        return catalogueService.getSettings(tenantId);
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasAuthority('packages:edit')")
    public PackageSettingsResponse updateSettings(@CurrentTenant UUID tenantId, @Valid @RequestBody PackageSettingsWriteRequest req) {
        return catalogueService.updateSettings(tenantId, req);
    }

    @PostMapping("/sell")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('packages:create')")
    public InstanceResponse sell(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody SellPackageRequest req) {
        return instanceService.sell(tenantId, staffId, req);
    }

    @GetMapping("/instances")
    @PreAuthorize("hasAuthority('packages:view')")
    public List<InstanceResponse> listInstances(@CurrentTenant UUID tenantId,
                                                @RequestParam(required = false) UUID patientId) {
        return instanceService.list(tenantId, patientId);
    }

    @GetMapping("/instances/{id}")
    @PreAuthorize("hasAuthority('packages:view')")
    public InstanceResponse getInstance(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return instanceService.detail(tenantId, id);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/instances/items/{itemId}/book")
    @PreAuthorize("hasAuthority('packages:edit')")
    public InstanceResponse book(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID itemId) {
        return instanceService.book(tenantId, staffId, itemId);
    }

    @PostMapping("/instances/items/{itemId}/redeem")
    @PreAuthorize("hasAuthority('packages:edit')")
    public InstanceResponse redeem(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID itemId) {
        return instanceService.redeem(tenantId, staffId, itemId);
    }

    @PostMapping("/instances/{id}/extend")
    @PreAuthorize("hasAuthority('packages:approve')")
    public InstanceResponse extend(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody ExtendRequest req) {
        return instanceService.extend(tenantId, staffId, id, req);
    }

    @GetMapping("/expiring-soon")
    @PreAuthorize("hasAuthority('packages:view')")
    public List<ExpiringSoonResponse> expiringSoon(@CurrentTenant UUID tenantId) {
        return instanceService.expiringSoon(tenantId);
    }

    @PostMapping("/instances/{id}/send-reminder")
    @PreAuthorize("hasAuthority('packages:edit')")
    public InstanceResponse sendReminder(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        return instanceService.sendReminder(tenantId, staffId, id);
    }

    @GetMapping("/instances/{id}/refund-preview")
    @PreAuthorize("hasAuthority('packages:view')")
    public RefundPreviewResponse refundPreview(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return instanceService.refundPreview(tenantId, id);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/instances/{id}/refund")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('packages:edit')")
    public RefundResponse requestRefund(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody RefundRequestRequest req) {
        return instanceService.requestRefund(tenantId, staffId, id, req);
    }

    @GetMapping("/refunds")
    @PreAuthorize("hasAuthority('packages:view')")
    public List<RefundResponse> listRefunds(@CurrentTenant UUID tenantId) {
        return instanceService.listRefunds(tenantId);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/refunds/{id}/approve")
    @PreAuthorize("hasAuthority('packages:approve')")
    public RefundResponse approveRefund(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                        @Valid @RequestBody(required = false) com.nabd.hms.packages.dto.ApproveRefundRequest req) {
        return instanceService.approveRefund(tenantId, staffId, id, req == null ? null : req.expectedAmount());
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/refunds/{id}/reject")
    @PreAuthorize("hasAuthority('packages:approve')")
    public RefundResponse rejectRefund(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                       @Valid @RequestBody com.nabd.hms.packages.dto.RejectRefundRequest req) {
        return instanceService.rejectRefund(tenantId, staffId, id, req.reason());
    }

    @GetMapping("/liability")
    @PreAuthorize("hasAuthority('packages:view')")
    public LiabilityResponse liability(@CurrentTenant UUID tenantId) {
        return instanceService.liability(tenantId);
    }
}
