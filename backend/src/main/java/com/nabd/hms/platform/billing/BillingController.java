package com.nabd.hms.platform.billing;

import com.nabd.hms.platform.billing.dto.DiscountPage;
import com.nabd.hms.platform.billing.dto.DiscountRequestCreate;
import com.nabd.hms.platform.billing.dto.DiscountResponse;
import com.nabd.hms.platform.billing.dto.SubscriptionPage;
import com.nabd.hms.platform.billing.dto.SubscriptionResponse;
import com.nabd.hms.platform.billing.dto.SubscriptionTransitionRequest;
import com.nabd.hms.platform.billing.dto.SubscriptionUpsertRequest;
import com.nabd.hms.common.RequestMeta;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Reads need billing_revenue:view (NB-257's matrix: super_admin, billing, commercial). Writes need billing_revenue:edit
 * (super_admin, billing); approving or rejecting a discount needs billing_revenue:approve (super_admin) — V2-005. */
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
    @PreAuthorize("hasAuthority('billing_revenue:edit')")
    public SubscriptionResponse upsertSubscription(@PathVariable UUID tenantId,
                                                     @Valid @RequestBody SubscriptionUpsertRequest req,
                                                     @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return billingService.upsert(tenantId, req, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }

    @PostMapping("/subscriptions/{tenantId}/transitions")
    @PreAuthorize("hasAuthority('billing_revenue:edit')")
    public SubscriptionResponse transition(@PathVariable UUID tenantId,
                                            @Valid @RequestBody SubscriptionTransitionRequest req,
                                            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return billingService.transition(tenantId, req.toStatus(), req.reason(), UUID.fromString(jwt.getSubject()),
                RequestMeta.clientIp(http));
    }

    @GetMapping("/discounts")
    public DiscountPage listDiscounts(@RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(required = false) String cursor) {
        return discountService.list(limit, cursor);
    }

    @PostMapping("/discounts")
    @PreAuthorize("hasAuthority('billing_revenue:edit')")
    public ResponseEntity<DiscountResponse> requestDiscount(@Valid @RequestBody DiscountRequestCreate req,
                                                              @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        DiscountResponse created = discountService.request(UUID.fromString(jwt.getSubject()), req, RequestMeta.clientIp(http));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/discounts/{id}/approve")
    @PreAuthorize("hasAuthority('billing_revenue:approve')")
    public DiscountResponse approveDiscount(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return discountService.approve(id, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }

    @PostMapping("/discounts/{id}/reject")
    @PreAuthorize("hasAuthority('billing_revenue:approve')")
    public DiscountResponse rejectDiscount(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return discountService.reject(id, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }
}
