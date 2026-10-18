package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.VitalsHistoryResponse;
import com.nabd.hms.clinical.dto.VitalsResponse;
import com.nabd.hms.clinical.dto.VitalsWriteRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical/vitals")
public class VitalsController {

    private final VitalsService service;

    VitalsController(VitalsService service) {
        this.service = service;
    }

    @GetMapping("/{queueEntryId}")
    @PreAuthorize("hasAuthority('clinical:view')")
    public VitalsResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.get(tenantId(jwt), queueEntryId);
    }

    @GetMapping("/patient/{patientId}/previous")
    @PreAuthorize("hasAuthority('clinical:view')")
    public VitalsHistoryResponse previous(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                           @RequestParam(defaultValue = "5") int limit) {
        return service.previous(tenantId(jwt), patientId, Math.max(1, Math.min(limit, 20)));
    }

    @PatchMapping("/{queueEntryId}")
    // REC-060: first-time recording needs clinical:create or clinical:edit; changing an already-recorded value needs clinical:edit.
    @PreAuthorize("hasAuthority('clinical:create') or hasAuthority('clinical:edit')")
    public VitalsResponse record(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                  @Valid @RequestBody VitalsWriteRequest req) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return service.record(tenantId(jwt), staffId(jwt), queueEntryId, req,
                permissions != null && permissions.contains("clinical:edit"));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
