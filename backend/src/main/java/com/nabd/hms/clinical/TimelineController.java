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
    private final ClinicalReadScope scopes;

    TimelineController(TimelineService service, ClinicalReadScope scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @com.nabd.hms.shared.phiread.PhiRead("timeline")
    @GetMapping
    @PreAuthorize("hasAuthority('clinical:view')")
    public EncounterPage get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                              @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return FieldGrantMasker.timeline(scope, service.get(scope.tenantId(), patientId, com.nabd.hms.common.PageRequest.limit(limit), cursor));
    }
}
