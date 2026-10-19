package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.AllergyCorrectionRequest;
import com.nabd.hms.clinical.dto.AllergyEventResponse;
import com.nabd.hms.clinical.dto.AllergyRemovalRequest;
import com.nabd.hms.clinical.dto.AllergyResponse;
import com.nabd.hms.clinical.dto.AllergyStatusResponse;
import com.nabd.hms.clinical.dto.AllergyWriteRequest;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical")
public class AllergyController {

    private final AllergyService service;
    private final ClinicalReadScope scopes;

    AllergyController(AllergyService service, ClinicalReadScope scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @GetMapping("/patients/{patientId}/allergies")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<AllergyResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                      @RequestParam(defaultValue = "false") boolean includeInactive) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return service.list(scope.tenantId(), patientId, includeInactive);
    }

    @PostMapping("/patients/{patientId}/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public AllergyResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                @Valid @RequestBody AllergyWriteRequest req, HttpServletRequest http) {
        return service.add(tenantId(jwt), patientId, staffId(jwt), RequestMeta.clientIp(http), req);
    }

    /** Correction (partial update) with a mandatory reason; the original stays in the history. */
    @PatchMapping("/allergies/{id}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public AllergyResponse correct(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody AllergyCorrectionRequest req, HttpServletRequest http) {
        return service.correct(tenantId(jwt), id, staffId(jwt), RequestMeta.clientIp(http), req);
    }

    /** Removal needs a reason; a body-less call is rejected (400 reason-required). */
    @PatchMapping("/allergies/{id}/deactivate")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public void deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                            @Valid @RequestBody(required = false) AllergyRemovalRequest req, HttpServletRequest http) {
        service.deactivate(tenantId(jwt), id, staffId(jwt), RequestMeta.clientIp(http), req);
    }

    @GetMapping("/patients/{patientId}/allergies/history")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<AllergyEventResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return service.history(scope.tenantId(), patientId);
    }

    @GetMapping("/patients/{patientId}/allergies/status")
    @PreAuthorize("hasAuthority('clinical:view')")
    public AllergyStatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return service.status(scope.tenantId(), patientId);
    }

    @PutMapping("/patients/{patientId}/allergies/status")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public AllergyStatusResponse documentNone(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, HttpServletRequest http) {
        return service.documentNoKnownAllergies(tenantId(jwt), patientId, staffId(jwt), RequestMeta.clientIp(http));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
