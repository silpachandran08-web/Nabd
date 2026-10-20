package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PrescriptionPrintHeaderResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical")
public class PrescriptionPrintController {

    private final PrescriptionPrintService service;
    private final ClinicalReadScope scopes;

    PrescriptionPrintController(PrescriptionPrintService service, ClinicalReadScope scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @org.springframework.transaction.annotation.Transactional // IDC-001: caller lookup, gate and read share one pool connection
    @com.nabd.hms.shared.phiread.PhiRead("prescription_print")
    @GetMapping("/prescriptions/{queueEntryId}/print-header")
    @PreAuthorize("hasAuthority('clinical:view')")
    public PrescriptionPrintHeaderResponse header(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requireVisit(scope, queueEntryId);
        return service.header(scope.tenantId(), queueEntryId);
    }
}
