package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PregnancyEventResponse;
import com.nabd.hms.clinical.dto.PregnancyStatusResponse;
import com.nabd.hms.clinical.dto.PregnancyStatusWriteRequest;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** DOC-021: pregnancy / lactation status. Same permissions as the allergy register: read clinical:view, write clinical:edit. */
@RestController
@RequestMapping("/v1/clinical")
public class PregnancyStatusController {

    private final PregnancyStatusService service;

    PregnancyStatusController(PregnancyStatusService service) {
        this.service = service;
    }

    @GetMapping("/patients/{patientId}/pregnancy-status")
    @PreAuthorize("hasAuthority('clinical:view')")
    public PregnancyStatusResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return service.get(tenantId(jwt), patientId);
    }

    @PutMapping("/patients/{patientId}/pregnancy-status")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public PregnancyStatusResponse put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                        @Valid @RequestBody PregnancyStatusWriteRequest req, HttpServletRequest http) {
        return service.record(tenantId(jwt), patientId, staffId(jwt), RequestMeta.clientIp(http), req);
    }

    @GetMapping("/patients/{patientId}/pregnancy-status/history")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<PregnancyEventResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return service.history(tenantId(jwt), patientId);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
