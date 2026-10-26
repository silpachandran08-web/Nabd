package com.nabd.hms.platform.access;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.platform.access.dto.DecisionRequest;
import com.nabd.hms.platform.access.dto.DecisionResponse;
import com.nabd.hms.platform.access.dto.OwnerRequestResponse;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PLT-057: the clinic owner's side of support access. Staff plane (an operator token is refused before this runs);
 * compliance:view / compliance:approve are held by the Owner role in the RBAC matrix. */
@Validated
@RestController
@RequestMapping("/v1/support-access/requests")
public class SupportAccessOwnerController {

    private final SupportAccessService service;

    SupportAccessOwnerController(SupportAccessService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('compliance:view')")
    public OwnerRequestResponse list(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.listRequests(status, limit);
    }

    @Idempotent
    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('compliance:approve')")
    public DecisionResponse decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest req,
                                   @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.decide(id, UUID.fromString(jwt.getSubject()), req, RequestMeta.clientIp(http));
    }
}
