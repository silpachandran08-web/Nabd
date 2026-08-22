package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.CheckoutContextResponse;
import com.nabd.hms.billing.dto.CheckoutRequest;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.OtcChargesResponse;
import com.nabd.hms.billing.dto.PaymentRequest;
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

@RestController
@RequestMapping("/v1/billing")
public class CheckoutController {

    private final CheckoutService service;

    CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @GetMapping("/checkout/{queueEntryId}")
    @PreAuthorize("hasAuthority('billing:view')")
    public CheckoutContextResponse checkoutContext(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.getCheckoutContext(tenantId(jwt), queueEntryId);
    }

    @GetMapping("/checkout/{queueEntryId}/invoice")
    @PreAuthorize("hasAuthority('billing:view')")
    public InvoiceResponse existingInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.getExistingInvoice(tenantId(jwt), queueEntryId);
    }

    @PostMapping("/checkout/{queueEntryId}")
    @PreAuthorize("hasAuthority('billing:create')")
    public InvoiceResponse checkout(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                     @Valid @RequestBody CheckoutRequest req) {
        return service.checkout(tenantId(jwt), queueEntryId, staffId(jwt), req);
    }

    @GetMapping("/otc-charges")
    @PreAuthorize("hasAuthority('billing:view')")
    public OtcChargesResponse otcCharges(@AuthenticationPrincipal Jwt jwt) {
        return service.getOtcCharges(tenantId(jwt));
    }

    @PostMapping("/otc-checkout")
    @PreAuthorize("hasAuthority('billing:create')")
    public InvoiceResponse otcCheckout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckoutRequest req) {
        return service.otcCheckout(tenantId(jwt), staffId(jwt), req);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('billing:view')")
    public InvoiceResponse getInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.getInvoice(tenantId(jwt), id);
    }

    @PostMapping("/invoices/{id}/payments")
    @PreAuthorize("hasAuthority('billing:edit')")
    public InvoiceResponse recordPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody PaymentRequest req) {
        return service.recordPayment(tenantId(jwt), id, staffId(jwt), req);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
