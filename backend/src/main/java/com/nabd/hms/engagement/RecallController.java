package com.nabd.hms.engagement;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.engagement.dto.RecallOrderRequest;
import com.nabd.hms.engagement.dto.RecallOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** COM-052: recall set at consult close. The encounter id is the visit's queue entry id (as in /v1/clinical/notes/{queueEntryId}). */
@RestController
@RequestMapping("/v1/encounters/{encounterId}/recall-orders")
public class RecallController {

    private final RecallService service;

    RecallController(RecallService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public RecallOrderResponse create(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID encounterId,
                                      @Valid @RequestBody RecallOrderRequest req) {
        return service.create(tenantId, staffId, jwt, encounterId, req);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<RecallOrderResponse> list(@CurrentTenant UUID tenantId, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID encounterId) {
        return service.forEncounter(tenantId, jwt, encounterId);
    }
}
