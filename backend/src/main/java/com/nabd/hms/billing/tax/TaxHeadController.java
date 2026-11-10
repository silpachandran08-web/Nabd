package com.nabd.hms.billing.tax;

import jakarta.validation.Valid;
import com.nabd.hms.common.RequestMeta;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TaxHeadController {

    private final TaxHeadService service;

    TaxHeadController(TaxHeadService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.Idempotent
    @PatchMapping("/v1/setup/charges/{id}/tax")
    @PreAuthorize("hasAuthority('setup:edit')")
    public TaxHeadService.Response setTax(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @Valid @RequestBody TaxHeadService.Request req, jakarta.servlet.http.HttpServletRequest http) {
        return service.update(UUID.fromString(jwt.getSubject()), id, RequestMeta.clientIp(http), req);
    }

    @GetMapping("/v1/billing/tax/guided-picker")
    @PreAuthorize("hasAuthority('setup:view')")
    public TaxHeadService.Picker picker(@AuthenticationPrincipal Jwt jwt, @RequestParam String jurisdiction) {
        return service.picker(jurisdiction);
    }
}
