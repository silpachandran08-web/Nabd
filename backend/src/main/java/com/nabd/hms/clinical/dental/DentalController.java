package com.nabd.hms.clinical.dental;

import com.nabd.hms.clinical.dental.dto.ToothResponse;
import com.nabd.hms.clinical.dental.dto.ToothWriteRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/specialty/dental/patients/{patientId}/chart")
public class DentalController {

    private final DentalService service;

    DentalController(DentalService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('specialty_dental:view')")
    public List<ToothResponse> chart(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return service.chart(tenantId(jwt), patientId);
    }

    @PatchMapping("/{toothNumber}")
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public ToothResponse upsertTooth(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                      @PathVariable int toothNumber, @Valid @RequestBody ToothWriteRequest req) {
        return service.upsertTooth(tenantId(jwt), patientId, toothNumber, staffId(jwt), req.status(), req.note());
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
