package com.nabd.hms.organization.dpa;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.organization.dpa.dto.DpaAcceptRequest;
import com.nabd.hms.organization.dpa.dto.DpaAcceptResponse;
import com.nabd.hms.organization.dpa.dto.DpaOtpChallengeResponse;
import com.nabd.hms.organization.dpa.dto.DpaStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.util.UUID;

/**
 * ADM-002. setup:dpa_sign is not a grant in the module-grant model, so signing needs setup:edit AND the clinic's Owner role
 * (403 NOT_OWNER otherwise, checked in DpaService).
 */
@RestController
@RequestMapping("/v1/setup/dpa")
class DpaController {

    private final DpaService service;

    DpaController(DpaService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    public DpaStatusResponse status() {
        return service.status();
    }

    @PostMapping("/otp")
    @PreAuthorize("hasAuthority('setup:edit')")
    public DpaOtpChallengeResponse requestOtp(@AuthenticationPrincipal Jwt jwt) {
        return service.requestOtp(UUID.fromString(jwt.getSubject()));
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/accept")
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.CREATED)
    public DpaAcceptResponse accept(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DpaAcceptRequest req, HttpServletRequest http) {
        return service.accept(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req);
    }

    @GetMapping("/acceptances/{id}/pdf")
    @PreAuthorize("hasAuthority('setup:view')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nabd-dpa.pdf\"").body(service.pdf(id));
    }
}
