package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.EncounterPage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical/patients/{patientId}/timeline")
public class TimelineController {

    private final TimelineService service;

    TimelineController(TimelineService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('clinical:view')")
    public EncounterPage get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                              @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return service.get(UUID.fromString(jwt.getClaimAsString("tenantId")), patientId, limit, cursor);
    }
}
