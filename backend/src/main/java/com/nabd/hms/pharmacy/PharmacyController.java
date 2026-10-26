package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.DispensingQueueEntryResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.PharmacyCapabilitiesResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemResponse;
import com.nabd.hms.pharmacy.dto.PharmacyScheduleClassResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemStatusRequest;
import com.nabd.hms.pharmacy.dto.PharmacyStockAdjustmentRequest;
import com.nabd.hms.pharmacy.dto.PharmacyStockMovementResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemWriteRequest;
import com.nabd.hms.pharmacy.dto.PharmacySettingsResponse;
import com.nabd.hms.pharmacy.dto.PharmacySettingsWriteRequest;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/v1/pharmacy")
public class PharmacyController {

    private final PharmacyService service;

    PharmacyController(PharmacyService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public PharmacySettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
        return service.getSettings(tenantId(jwt));
    }

    @PatchMapping("/settings")
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacySettingsResponse updateSettings(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody PharmacySettingsWriteRequest req, HttpServletRequest http) {
        return service.updateSettings(tenantId(jwt), staffId(jwt), RequestMeta.clientIp(http), req);
    }

    @GetMapping("/items")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<PharmacyItemResponse> items(@AuthenticationPrincipal Jwt jwt,
                                            @org.springframework.web.bind.annotation.RequestParam(name = "class", required = false) String scheduleClass) {
        return service.listItems(tenantId(jwt), scheduleClass);
    }

    @PostMapping("/items")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public ResponseEntity<PharmacyItemResponse> addItem(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody PharmacyItemWriteRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addItem(tenantId(jwt), staffId(jwt), RequestMeta.clientIp(http), req));
    }

    @PatchMapping("/items/{id}")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacyItemResponse updateItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody PharmacyItemWriteRequest req, HttpServletRequest http) {
        return service.updateItem(tenantId(jwt), staffId(jwt), RequestMeta.clientIp(http), id, req);
    }

    public record DrugLinkRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String drugId) {
    }

    /** CLN-019: map the item to a drug master product (idempotent: same body, same result). */
    @org.springframework.web.bind.annotation.PutMapping("/items/{id}/drug-link")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public java.util.Map<String, Object> linkDrug(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody DrugLinkRequest req, HttpServletRequest http) {
        return service.linkDrug(tenantId(jwt), staffId(jwt), RequestMeta.clientIp(http), id, req.drugId());
    }

    /** V2-004: the only way to change stock by hand. Relative (`delta`) or stock-take (`setTo`), reason required. */
    @PostMapping("/items/{id}/stock-adjustments")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacyItemResponse adjustStock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @Valid @RequestBody PharmacyStockAdjustmentRequest req, HttpServletRequest http) {
        return service.adjustStock(tenantId(jwt), staffId(jwt), RequestMeta.clientIp(http), id, req);
    }

    /** V2-007: activate / deactivate an item. */
    @PatchMapping("/items/{id}/status")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacyItemResponse setStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @Valid @RequestBody PharmacyItemStatusRequest req, HttpServletRequest http) {
        return service.setActive(tenantId(jwt), staffId(jwt), RequestMeta.clientIp(http), id, req);
    }

    @GetMapping("/items/{id}/stock-movements")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<PharmacyStockMovementResponse> stockMovements(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.movements(tenantId(jwt), id);
    }

    @GetMapping("/dispensing-queue")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<DispensingQueueEntryResponse> dispensingQueue(@AuthenticationPrincipal Jwt jwt) {
        return service.dispensingQueue(tenantId(jwt));
    }

    /** PHL-004: the pharmacy features this clinic's mode switches on; any signed-in staff member (the nav is built from it). */
    @GetMapping("/capabilities")
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("isAuthenticated()")
    public PharmacyCapabilitiesResponse capabilities() {
        return service.capabilities();
    }

    /** PHL-004: lower-cased names of items flagged "buy outside", for the doctor's Rx. */
    @GetMapping("/buy-outside")
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("isAuthenticated()")
    public List<String> buyOutside() {
        return service.buyOutside();
    }

    /** PHL-002: the schedule classes valid for the clinic's market with their register / Rx rules. */
    @GetMapping("/schedule-classes")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<PharmacyScheduleClassResponse> scheduleClasses() {
        return service.scheduleClasses();
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
