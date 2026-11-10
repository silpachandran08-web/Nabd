package com.nabd.hms.messaging;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** COM-012: called by the provisioning saga (platform operator token; mTLS is enforced at the edge, not here). */
@RestController
class TemplateCatalogueController {

    private final TemplateCatalogueService service;

    TemplateCatalogueController(TemplateCatalogueService service) {
        this.service = service;
    }

    public record InstantiateRequest(List<String> languages) {
    }

    @PostMapping("/internal/v1/tenants/{tenantId}/templates:instantiate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('onboarding_provisioning:view')")
    Map<String, Integer> instantiate(@PathVariable UUID tenantId, @Valid @RequestBody InstantiateRequest req) {
        return Map.of("submitted", service.instantiate(tenantId, req.languages()));
    }
}
