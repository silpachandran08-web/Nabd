package com.nabd.hms.patient.internal.privacy;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * PLT-047: the desk logs a walk-in's data request (patients:edit); the owner reads the privacy queue (compliance:view). The Nabd
 * DPO reads the cross-clinic queue of reminded and overdue requests (operator plane, audit_compliance:view; ids and dates only).
 * The patient-web intake (/v1/me/dsr-requests) waits on the patient session, which Core does not have yet.
 */
@RestController
class DsrController {

    /** channel is always 'desk' here; requesterPatientId is the guardian when the request is for a child. */
    @Schema(name = "DsrRequestCreate")
    record CreateBody(@NotNull UUID patientId, @NotBlank @Pattern(regexp = "access|correction|erasure") String type,
                      @Pattern(regexp = "desk") String channel, @Size(max = 2000) String details, UUID requesterPatientId) {
    }

    @Schema(name = "DsrDpoQueue")
    record DpoQueue(List<DsrService.DpoItem> items) {
    }

    private final DsrService service;

    DsrController(DsrService service) {
        this.service = service;
    }

    @Idempotent
    @PostMapping("/v1/privacy/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('patients:edit')")
    DsrService.Created create(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http, @Valid @RequestBody CreateBody b) {
        return service.createAtDesk(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), b.patientId(), b.requesterPatientId(), b.type(), b.details()).orThrow();
    }

    @GetMapping("/v1/privacy/requests")
    @PreAuthorize("hasAuthority('compliance:view')")
    DsrService.Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String type, @RequestParam(required = false) String cursor) {
        return service.list(status, type, cursor);
    }

    @GetMapping("/v1/platform/privacy/requests")
    @PreAuthorize("hasAuthority('audit_compliance:view')")
    DpoQueue dpoQueue() {
        return new DpoQueue(service.dpoQueue());
    }
}
