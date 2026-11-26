package com.nabd.hms.lab;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.lab.LabOrderDtos.CancelRequest;
import com.nabd.hms.lab.LabOrderDtos.Cancelled;
import com.nabd.hms.lab.LabOrderDtos.ExternalSheetRequest;
import com.nabd.hms.lab.LabOrderDtos.Favourites;
import com.nabd.hms.lab.LabOrderDtos.OrderList;
import com.nabd.hms.lab.LabOrderDtos.OrderPlaced;
import com.nabd.hms.lab.LabOrderDtos.OrderRequest;
import com.nabd.hms.shared.Idempotent;
import com.nabd.hms.shared.phiread.PhiRead;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * PHL-043: lab orders from the consult. Everything under /v1/lab needs the clinic's `lab` entitlement (LabEntitlementGuard: 403
 * module-not-entitled) and lab:order, the doctor's grant. The one path outside it, under /v1/clinical, is the order sheet for a lab
 * outside Nabd that a clinic WITHOUT the lab module prints from the consult (clinical:edit; no catalogue, no order row).
 */
@RestController
public class LabOrderController {

    private final LabOrderService service;

    LabOrderController(LabOrderService service) {
        this.service = service;
    }

    /** 422 test-inactive, 422 duplicate-test, 422 partner-lab-unknown, 409 visit-closed. */
    @Idempotent
    @PostMapping("/v1/lab/orders")
    @PreAuthorize("hasAuthority('lab:order')")
    public ResponseEntity<OrderPlaced> place(@AuthenticationPrincipal Jwt jwt, @CurrentStaff UUID staffId, @Valid @RequestBody OrderRequest req,
                                             HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.place(jwt, staffId, RequestMeta.clientIp(http), req));
    }

    /** The visit's orders, STAT first. */
    @PhiRead("lab_order_list")
    @GetMapping("/v1/lab/orders")
    @PreAuthorize("hasAuthority('lab:order')")
    public OrderList forVisit(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID queueEntryId) {
        return service.forVisit(jwt, queueEntryId);
    }

    /** Cancels an order nothing was collected for and takes its charges off the visit's checkout. 409 already-collected. */
    @Idempotent
    @PostMapping("/v1/lab/orders/{id}/cancel")
    @PreAuthorize("hasAuthority('lab:order')")
    public Cancelled cancel(@AuthenticationPrincipal Jwt jwt, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody CancelRequest req,
                            HttpServletRequest http) {
        return service.cancel(jwt, staffId, RequestMeta.clientIp(http), id, req.reason());
    }

    /** The printable order sheet (A4 PDF: patient, tests, the order reference as a Code 128 barcode). */
    @GetMapping("/v1/lab/orders/{id}/sheet")
    @PreAuthorize("hasAuthority('lab:order')")
    public ResponseEntity<byte[]> sheet(@AuthenticationPrincipal Jwt jwt, @CurrentStaff UUID staffId, @PathVariable UUID id, HttpServletRequest http) {
        return pdf(service.sheet(jwt, staffId, RequestMeta.clientIp(http), id));
    }

    @GetMapping("/v1/lab/order-favourites")
    @PreAuthorize("hasAuthority('lab:order')")
    public Favourites favourites(@CurrentStaff UUID staffId) {
        return service.favourites(staffId);
    }

    /** For a clinic without the lab module: an order sheet for an outside lab with the tests as typed. Not under /v1/lab on purpose. */
    @PostMapping("/v1/clinical/visits/{queueEntryId}/lab-order-sheet")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public ResponseEntity<byte[]> externalSheet(@AuthenticationPrincipal Jwt jwt, @CurrentStaff UUID staffId, @PathVariable UUID queueEntryId,
                                                @Valid @RequestBody ExternalSheetRequest req, HttpServletRequest http) {
        return pdf(service.externalSheet(jwt, staffId, RequestMeta.clientIp(http), queueEntryId, req));
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline; filename=\"lab-order.pdf\"").body(bytes);
    }
}
