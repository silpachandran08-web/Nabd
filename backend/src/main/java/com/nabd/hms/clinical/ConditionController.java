package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.ConditionResponse;
import com.nabd.hms.clinical.dto.ConditionWriteRequest;
import com.nabd.hms.clinical.dto.DueConditionResponse;
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
public class ConditionController {

    private final ConditionService service;

    ConditionController(ConditionService service) {
        this.service = service;
    }

    @GetMapping("/patients/{patientId}/conditions")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<ConditionResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return service.list(tenantId(jwt), patientId);
    }

    @PostMapping("/patients/{patientId}/conditions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public ConditionResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                  @Valid @RequestBody ConditionWriteRequest req) {
        return service.add(tenantId(jwt), patientId, staffId(jwt), req);
    }

    @PatchMapping("/conditions/{id}/resolve")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public void resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.resolve(tenantId(jwt), id);
    }

    /** NB-077's clinic-wide "chronic review due" list — derived from review_due_date, not a manual flag. */
    @GetMapping("/conditions/due")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<DueConditionResponse> due(@AuthenticationPrincipal Jwt jwt) {
        return service.due(tenantId(jwt));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
