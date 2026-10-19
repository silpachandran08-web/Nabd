package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.BreakGlassActivateRequest;
import com.nabd.hms.auth.dto.BreakGlassActivateResponse;
import com.nabd.hms.auth.dto.BreakGlassAlertResponse;
import com.nabd.hms.auth.dto.BreakGlassListResponse;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PLT-012: emergency (break-glass) access. */
@RestController
@RequestMapping("/v1/auth/break-glass")
class BreakGlassController {

    private final BreakGlassService service;

    BreakGlassController(BreakGlassService service) {
        this.service = service;
    }

    @PostMapping("/activate")
    @PreAuthorize("hasAuthority('clinical:breakGlass')")
    @ResponseStatus(HttpStatus.CREATED)
    BreakGlassActivateResponse activate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BreakGlassActivateRequest req,
                                        @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
                                        HttpServletRequest http) {
        return service.activate(jwt, req, stepUpToken, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    // holder-or-staff:edit is decided in the service (it needs the grant's holder)
    @PostMapping("/{id}/deactivate")
    ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deactivate(jwt, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('staff:view')")
    BreakGlassListResponse active(@AuthenticationPrincipal Jwt jwt) {
        return service.listActive(UUID.fromString(jwt.getClaimAsString("tenantId")));
    }

    /** The calling Owner's own in-app alerts (polled; there is no push channel yet). */
    @GetMapping("/alerts")
    BreakGlassAlertResponse alerts(@AuthenticationPrincipal Jwt jwt) {
        return service.myAlerts(UUID.fromString(jwt.getClaimAsString("tenantId")), UUID.fromString(jwt.getSubject()));
    }
}
