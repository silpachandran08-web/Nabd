package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.ActiveMedicinesResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** CLN-032: the patient's current medicines as the Rx pad checks see them. */
@RestController
@RequestMapping("/v1/clinical")
public class ActiveMedicinesController {

    private final ActiveMedicinesService service;
    private final ClinicalReadScope scopes;

    ActiveMedicinesController(ActiveMedicinesService service, ClinicalReadScope scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @org.springframework.transaction.annotation.Transactional // IDC-001: caller lookup, gate and read share one pool connection
    @com.nabd.hms.shared.phiread.PhiRead("active_medicines")
    @GetMapping("/patients/{patientId}/active-medicines")
    @PreAuthorize("hasAuthority('clinical:view')")
    public ActiveMedicinesResponse activeMedicines(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return service.list(scope.tenantId(), patientId);
    }
}
