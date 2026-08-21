package com.nabd.hms.platform.access;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.platform.access.dto.GrantResponse;
import com.nabd.hms.platform.access.dto.PatientViewResponse;
import com.nabd.hms.platform.access.dto.RequestGrantRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Gated on support_access:view — per NB-257's matrix, super_admin/implementation/support_engineer. */
@RestController
@RequestMapping("/v1/platform/support-access")
@PreAuthorize("hasAuthority('support_access:view')")
public class SupportAccessController {

    private final SupportAccessService service;

    SupportAccessController(SupportAccessService service) {
        this.service = service;
    }

    @PostMapping("/grants")
    public ResponseEntity<GrantResponse> requestGrant(@Valid @RequestBody RequestGrantRequest req,
                                                        @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        GrantResponse grant = service.requestGrant(req.tenantId(), operatorId(jwt), req.reason(), RequestMeta.clientIp(http));
        return ResponseEntity.status(HttpStatus.CREATED).body(grant);
    }

    @GetMapping("/grants")
    public List<GrantResponse> listGrants() {
        return service.listGrants();
    }

    @PostMapping("/grants/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        service.revoke(id, operatorId(jwt), RequestMeta.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/grants/{id}/patients/{patientId}")
    public PatientViewResponse viewPatient(@PathVariable UUID id, @PathVariable UUID patientId,
                                            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.viewPatient(id, patientId, operatorId(jwt), RequestMeta.clientIp(http));
    }

    private UUID operatorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
