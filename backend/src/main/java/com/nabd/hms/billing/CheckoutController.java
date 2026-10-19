package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.CheckoutContextResponse;
import com.nabd.hms.billing.dto.CheckoutRequest;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.OtcChargesResponse;
import com.nabd.hms.billing.dto.PaymentRequest;
import com.nabd.hms.billing.dto.VoidInvoiceRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @com.nabd.hms.shared.idempotency.Idempotent
    @PostMapping("/checkout/{queueEntryId}")
    @PreAuthorize("hasAuthority('billing:create')")
    public InvoiceResponse checkout(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                     @Valid @RequestBody CheckoutRequest req, jakarta.servlet.http.HttpServletRequest http) {
        return service.checkout(tenantId(jwt), queueEntryId, staffId(jwt), canDiscount(jwt), RequestMeta.clientIp(http), req);
    }

    @GetMapping("/otc-charges")
    @PreAuthorize("hasAuthority('billing:view')")
    public OtcChargesResponse otcCharges(@AuthenticationPrincipal Jwt jwt) {
        return service.getOtcCharges(tenantId(jwt));
    }

    @com.nabd.hms.shared.idempotency.Idempotent
    @PostMapping("/otc-checkout")
    @PreAuthorize("hasAuthority('billing:create')")
    public InvoiceResponse otcCheckout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckoutRequest req,
                                        jakarta.servlet.http.HttpServletRequest http) {
        return service.otcCheckout(tenantId(jwt), staffId(jwt), canDiscount(jwt), RequestMeta.clientIp(http), req);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('billing:view')")
    public InvoiceResponse getInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.getInvoice(tenantId(jwt), id);
    }

    @com.nabd.hms.shared.idempotency.Idempotent
    @PostMapping("/invoices/{id}/payments")
    @PreAuthorize("hasAuthority('billing:edit')")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse recordPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody PaymentRequest req,
                                          @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.recordPayment(tenantId(jwt), id, staffId(jwt), req, idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim());
    }

    /** PHA-011: void an unpaid invoice and restore its pharmacy stock. Same grant as discounts/refunds. */
    @PostMapping("/invoices/{id}/void")
    @PreAuthorize("hasAuthority('billing:edit')")
    public InvoiceResponse voidInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @Valid @RequestBody VoidInvoiceRequest req, jakarta.servlet.http.HttpServletRequest http) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions == null || !permissions.contains("billing.discount")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "void-not-permitted", "Void not permitted",
                    "Your role cannot void an invoice.");
        }
        return service.voidInvoice(tenantId(jwt), id, staffId(jwt), RequestMeta.clientIp(http), req);
    }

    /** REC-043 / FIN-003: the billing.discount grant gates every price-bearing change; CheckoutService enforces it per field. */
    private boolean canDiscount(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions != null && permissions.contains("billing.discount");
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
