package com.nabd.hms.setup;

import com.nabd.hms.setup.dto.ComplianceHealthResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/compliance")
class ComplianceController {

    private final SetupService service;

    ComplianceController(SetupService service) {
        this.service = service;
    }

    // same permission as the licence registry read (GET /v1/setup/licences)
    @GetMapping("/health")
    @PreAuthorize("hasAuthority('setup:view')")
    public ComplianceHealthResponse health(@AuthenticationPrincipal Jwt jwt) {
        return service.complianceHealth(UUID.fromString(jwt.getClaimAsString("tenantId")));
    }
}
