package com.nabd.hms.platform.billing;

import com.nabd.hms.platform.billing.dto.DiscountPage;
import com.nabd.hms.platform.billing.dto.DiscountRequestCreate;
import com.nabd.hms.platform.billing.dto.DiscountResponse;
import com.nabd.hms.platform.billing.dto.SubscriptionPage;
import com.nabd.hms.platform.billing.dto.SubscriptionResponse;
import com.nabd.hms.platform.billing.dto.SubscriptionTransitionRequest;
import com.nabd.hms.platform.billing.dto.SubscriptionUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Gated on billing_revenue:view (NB-257's matrix) — super_admin, billing, commercial. */
@RestController
@RequestMapping("/v1/platform/billing")
@PreAuthorize("hasAuthority('billing_revenue:view')")
public class BillingController {

    private final BillingService billingService;
    private final DiscountService discountService;

    BillingController(BillingService billingService, DiscountService discountService) {
        this.billingService = billingService;
        this.discountService = discountService;
    }

    @GetMapping("/subscriptions")
    public SubscriptionPage listSubscriptions(@RequestParam(defaultValue = "50") int limit,
                                               @RequestParam(required = false) String cursor) {
        return billingService.list(limit, cursor);
    }

    @PostMapping("/subscriptions/{tenantId}")
    public SubscriptionResponse upsertSubscription(@PathVariable UUID tenantId,
                                                     @Valid @RequestBody SubscriptionUpsertRequest req) {
        return billingService.upsert(tenantId, req);
    }

    @PostMapping("/subscriptions/{tenantId}/transitions")
    public SubscriptionResponse transition(@PathVariable UUID tenantId,
                                            @Valid @RequestBody SubscriptionTransitionRequest req,
                                            @AuthenticationPrincipal Jwt jwt) {
        return billingService.transition(tenantId, req.toStatus(), req.reason(), UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/discounts")
    public DiscountPage listDiscounts(@RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(required = false) String cursor) {
        return discountService.list(limit, cursor);
    }

    @PostMapping("/discounts")
    public ResponseEntity<DiscountResponse> requestDiscount(@Valid @RequestBody DiscountRequestCreate req,
                                                              @AuthenticationPrincipal Jwt jwt) {
        DiscountResponse created = discountService.request(UUID.fromString(jwt.getSubject()), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/discounts/{id}/approve")
    public DiscountResponse approveDiscount(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return discountService.approve(id, UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/discounts/{id}/reject")
    public DiscountResponse rejectDiscount(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return discountService.reject(id, UUID.fromString(jwt.getSubject()));
    }
}
