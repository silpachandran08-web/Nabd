package com.nabd.hms.pharmacy;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.GoodsReceiptRequest;
import com.nabd.hms.pharmacy.dto.GoodsReceiptResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PHL-007: direct goods receipt (supplier bill, no purchase order). */
@RestController
@RequestMapping("/v1/pharmacy")
public class GoodsReceiptController {

    private final GoodsReceiptService service;

    GoodsReceiptController(GoodsReceiptService service) {
        this.service = service;
    }

    // pharmacy:purchase is not a module:action pair of the RBAC model; receiving stock is a stock write, so it takes pharmacy:edit like adjustments.
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/receipts")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public ResponseEntity<GoodsReceiptResponse> post(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GoodsReceiptRequest req,
                                                     HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.post(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req));
    }
}
