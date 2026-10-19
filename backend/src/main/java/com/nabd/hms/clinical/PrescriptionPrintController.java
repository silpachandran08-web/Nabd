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

    PrescriptionPrintController(PrescriptionPrintService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.phiread.PhiRead("prescription_print")
    @GetMapping("/prescriptions/{queueEntryId}/print-header")
    @PreAuthorize("hasAuthority('clinical:view')")
    public PrescriptionPrintHeaderResponse header(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.header(UUID.fromString(jwt.getClaimAsString("tenantId")), queueEntryId);
    }
}
