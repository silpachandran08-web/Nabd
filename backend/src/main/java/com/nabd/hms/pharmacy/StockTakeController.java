package com.nabd.hms.pharmacy;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.StockTakeDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** PHL-010: stock take by batch and the reasoned batch adjustment (maker-checker for controlled drugs). Same gates as the other stock writes. */
@RestController
@RequestMapping("/v1/pharmacy")
public class StockTakeController {

    private final StockTakeService service;

    StockTakeController(StockTakeService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/stock-takes")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public ResponseEntity<StockTakeDtos.StartResponse> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StockTakeDtos.StartRequest req,
                                                             HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.start(staffId(jwt), RequestMeta.clientIp(http), req));
    }

    @GetMapping("/stock-takes")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<StockTakeDtos.Summary> list() {
        return service.list();
    }

    @GetMapping("/stock-takes/{id}")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public StockTakeDtos.Sheet sheet(@PathVariable UUID id) {
        return service.sheet(id);
    }

    @PutMapping("/stock-takes/{id}/counts")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public StockTakeDtos.CountsResponse counts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody StockTakeDtos.CountsRequest req) {
        return service.counts(staffId(jwt), id, req);
    }

    /** Idempotent: a posted session answers with what it wrote. A controlled variance needs approverStaffId + approverPin. */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/stock-takes/{id}/post")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public StockTakeDtos.PostResponse post(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @Valid @RequestBody(required = false) StockTakeDtos.PostRequest req, HttpServletRequest http) {
        return service.post(staffId(jwt), RequestMeta.clientIp(http), id, req);
    }

    @PostMapping("/stock-takes/{id}/cancel")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public StockTakeDtos.Summary cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        return service.cancel(staffId(jwt), RequestMeta.clientIp(http), id);
    }

    /** One batch: delta or counted total, a reason code from the fixed list, and the second signer for a controlled drug. */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/batches/{id}/adjustments")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public StockTakeDtos.BatchAdjustResponse adjust(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                    @Valid @RequestBody StockTakeDtos.BatchAdjustRequest req, HttpServletRequest http) {
        return service.adjust(staffId(jwt), RequestMeta.clientIp(http), id, req);
    }

    /** Who can give the second sign-off (holders of pharmacy:adjust_approve other than the caller), for the approver picker. */
    @GetMapping("/adjustment-approvers")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public List<StockTakeDtos.Approver> approvers(@AuthenticationPrincipal Jwt jwt) {
        return service.approvers(staffId(jwt));
    }

    private static UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
