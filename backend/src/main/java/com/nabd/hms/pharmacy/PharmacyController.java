package com.nabd.hms.pharmacy;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.pharmacy.dto.BatchDtos;
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
    private final BatchService batchService;

    PharmacyController(PharmacyService service, BatchService batchService) {
        this.service = service;
        this.batchService = batchService;
    }

    @GetMapping("/settings")
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public PharmacySettingsResponse settings(@CurrentTenant UUID tenantId) {
        return service.getSettings(tenantId);
    }

    @PatchMapping("/settings")
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacySettingsResponse updateSettings(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                     @Valid @RequestBody PharmacySettingsWriteRequest req, HttpServletRequest http) {
        return service.updateSettings(tenantId, staffId, RequestMeta.clientIp(http), req);
    }

    @GetMapping("/items")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<PharmacyItemResponse> items(@CurrentTenant UUID tenantId,
                                            @org.springframework.web.bind.annotation.RequestParam(name = "class", required = false) String scheduleClass) {
        return service.listItems(tenantId, scheduleClass);
    }

    @PostMapping("/items")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public ResponseEntity<PharmacyItemResponse> addItem(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                         @Valid @RequestBody PharmacyItemWriteRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addItem(tenantId, staffId, RequestMeta.clientIp(http), req));
    }

    @PatchMapping("/items/{id}")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacyItemResponse updateItem(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                            @Valid @RequestBody PharmacyItemWriteRequest req, HttpServletRequest http) {
        return service.updateItem(tenantId, staffId, RequestMeta.clientIp(http), id, req);
    }

    public record DrugLinkRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String drugId) {
    }

    /** CLN-019: map the item to a drug master product (idempotent: same body, same result). */
    @org.springframework.web.bind.annotation.PutMapping("/items/{id}/drug-link")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public java.util.Map<String, Object> linkDrug(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                                  @Valid @RequestBody DrugLinkRequest req, HttpServletRequest http) {
        return service.linkDrug(tenantId, staffId, RequestMeta.clientIp(http), id, req.drugId());
    }

    /** V2-004: the only way to change stock by hand. Relative (`delta`) or stock-take (`setTo`), reason required. */
    @PostMapping("/items/{id}/stock-adjustments")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacyItemResponse adjustStock(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                             @Valid @RequestBody PharmacyStockAdjustmentRequest req, HttpServletRequest http) {
        return service.adjustStock(tenantId, staffId, RequestMeta.clientIp(http), id, req);
    }

    /** V2-007: activate / deactivate an item. */
    @PatchMapping("/items/{id}/status")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacyItemResponse setStatus(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                           @Valid @RequestBody PharmacyItemStatusRequest req, HttpServletRequest http) {
        return service.setActive(tenantId, staffId, RequestMeta.clientIp(http), id, req);
    }

    @GetMapping("/items/{id}/stock-movements")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<PharmacyStockMovementResponse> stockMovements(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        return service.movements(tenantId, id);
    }

    @GetMapping("/dispensing-queue")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<DispensingQueueEntryResponse> dispensingQueue(@CurrentTenant UUID tenantId) {
        return service.dispensingQueue(tenantId);
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

    /** PHL-005: batches of one item in FEFO order (Hybrid keeps one implicit batch and gets it without batch fields). */
    @GetMapping("/items/{id}/batches")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<BatchDtos.BatchResponse> batches(@PathVariable UUID id,
                                                 @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeEmpty) {
        return batchService.list(id, includeEmpty);
    }

    /** PHL-005: stock list (on-hand, nearest expiry, value), keyset pagination. */
    @GetMapping("/stock")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public BatchDtos.StockPageResponse stock(@org.springframework.web.bind.annotation.RequestParam(required = false) String q,
                                             @org.springframework.web.bind.annotation.RequestParam(name = "class", required = false) String scheduleClass,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) @jakarta.validation.constraints.Min(0) Integer expiringWithinDays,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor,
                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        return batchService.stock(q, scheduleClass, expiringWithinDays, cursor, limit);
    }

    @GetMapping("/batches/{id}/movements")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<BatchDtos.BatchMovementResponse> batchMovements(@PathVariable UUID id) {
        return batchService.movements(id);
    }

    /** PHL-005: correct batch number / expiry / location (never quantity). */
    @PatchMapping("/batches/{id}")
    @RequiresPharmacyMode(PharmacyMode.HYBRID) // BatchService refuses it in Hybrid once the batch is known (an unknown id stays 404)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public BatchDtos.BatchResponse patchBatch(@CurrentStaff UUID staffId, @PathVariable UUID id,
                                              @Valid @RequestBody BatchDtos.BatchPatchRequest req, HttpServletRequest http) {
        return batchService.patch(staffId, RequestMeta.clientIp(http), id, req);
    }

    /** PHL-006: FEFO pick preview, no stock change. Authority is pharmacy:view: the permission catalogue has no dispense action. */
    @PostMapping("/picks")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public BatchDtos.PickResponse picks(@Valid @RequestBody BatchDtos.PickRequest req) {
        return batchService.pick(req);
    }
}
