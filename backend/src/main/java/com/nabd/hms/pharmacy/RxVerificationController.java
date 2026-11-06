package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.RxVerificationDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** PHL-013: verify a scanned Rx QR. Idempotent (each call adds one audit row and changes nothing else). */
@RestController
@RequestMapping("/v1/pharmacy/rx-verifications")
public class RxVerificationController {

    private final RxVerificationService service;

    RxVerificationController(RxVerificationService service) {
        this.service = service;
    }

    /** 422 qr-unreadable when the text is not an Rx QR; otherwise 200 with status valid, expired, dispensed or invalid. */
    @PostMapping
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public RxVerificationDtos.Response verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RxVerificationDtos.Request req) {
        return service.verify(req.qrPayload(), UUID.fromString(jwt.getSubject()));
    }

    /** The Rx signing keys' public JWKS (ES256), which the browser caches to verify signature and expiry offline. */
    @GetMapping("/keys")
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public Map<String, Object> keys() {
        return service.publicKeys();
    }
}
