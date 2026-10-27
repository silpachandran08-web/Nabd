package com.nabd.hms.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** FIN-020: UPI payment link / QR for an invoice, intent status, and the public HMAC-signed gateway webhook. */
@RestController
@RequestMapping("/v1/billing")
class PaymentIntentController {

    @Schema(name = "PaymentIntentRequest")
    record CreateRequest(@NotNull @Pattern(regexp = "link|qr") String kind,
                         @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                         @Pattern(regexp = "patient|caregiver") String recipient, UUID caregiverId) {
    }

    @Schema(name = "PaymentIntentCreated")
    record CreatedResponse(UUID intentId, String url, String qrPayload, Instant expiresAt) {
    }

    @Schema(name = "PaymentIntentStatus")
    record StatusResponse(String status, Instant paidAt, String gatewayRef) {
    }

    private final PaymentIntentService service;

    PaymentIntentController(PaymentIntentService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/invoices/{id}/payment-intents")
    @PreAuthorize("hasAuthority('billing:edit')")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    CreatedResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CreateRequest req) {
        var c = service.create(id, req.kind(), req.amount(), req.recipient(), req.caregiverId(), UUID.fromString(jwt.getSubject()));
        return new CreatedResponse(c.intentId(), c.url(), c.qrPayload(), c.expiresAt());
    }

    @GetMapping("/payment-intents/{id}")
    @PreAuthorize("hasAuthority('billing:view')")
    StatusResponse status(@PathVariable UUID id) {
        var s = service.status(id);
        return new StatusResponse(s.status(), s.paidAt(), s.gatewayRef());
    }

    /** Public: the gateway cannot send our JWT, so the HMAC of the raw body is the only credential. Body is never logged. */
    @PostMapping("/gateway-webhooks/{provider}/{tenantKey}")
    ResponseEntity<Void> webhook(@PathVariable String provider, @PathVariable String tenantKey, @RequestBody byte[] body,
                                 @RequestHeader(value = "X-Razorpay-Signature", required = false) String razorpay,
                                 @RequestHeader(value = "X-Webhook-Signature", required = false) String generic) {
        service.receive(provider, tenantKey, body, razorpay != null ? razorpay : generic);
        return ResponseEntity.ok().build();
    }
}
