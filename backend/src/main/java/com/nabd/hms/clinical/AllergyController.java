package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.AllergyResponse;
import com.nabd.hms.clinical.dto.AllergyWriteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical")
public class AllergyController {

    private final AllergyService service;

    AllergyController(AllergyService service) {
        this.service = service;
    }

    @GetMapping("/patients/{patientId}/allergies")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<AllergyResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return service.list(tenantId(jwt), patientId);
    }

    @PostMapping("/patients/{patientId}/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public AllergyResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                @Valid @RequestBody AllergyWriteRequest req) {
        return service.add(tenantId(jwt), patientId, staffId(jwt), req);
    }

    @PatchMapping("/allergies/{id}/deactivate")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public void deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deactivate(tenantId(jwt), id);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
