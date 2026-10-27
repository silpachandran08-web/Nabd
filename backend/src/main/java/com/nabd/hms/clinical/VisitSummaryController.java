package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.VisitSummaryResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** CLN-008: GET /v1/clinical/visits/{queueEntryId}/summary, the solo doctor's one-call pre-consult read. */
@RestController
@RequestMapping("/v1/clinical/visits/{queueEntryId}/summary")
public class VisitSummaryController {

    private final VisitSummaryService service;
    private final ClinicalReadScope scopes;

    VisitSummaryController(VisitSummaryService service, ClinicalReadScope scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @org.springframework.transaction.annotation.Transactional // IDC-001: caller lookup, gate and read share one pool connection
    @com.nabd.hms.shared.phiread.PhiRead("visit_summary")
    @GetMapping
    @PreAuthorize("hasAuthority('clinical:view')")
    public VisitSummaryResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requireVisit(scope, queueEntryId);
        return service.get(scope, queueEntryId);
    }
}
