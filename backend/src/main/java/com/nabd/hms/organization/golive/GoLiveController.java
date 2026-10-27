package com.nabd.hms.organization.golive;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.organization.golive.dto.GoLiveChecksResponse;
import com.nabd.hms.organization.golive.dto.GoLiveResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADM-011. Going live needs setup:edit AND the clinic's Owner role (403 NOT_OWNER, checked in GoLiveService). */
@RestController
@RequestMapping("/v1/setup")
class GoLiveController {

    private final GoLiveService service;

    GoLiveController(GoLiveService service) {
        this.service = service;
    }

    @GetMapping("/go-live/checks")
    @PreAuthorize("hasAuthority('setup:view')")
    public GoLiveChecksResponse checks() {
        return service.checks();
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/go-live")
    @PreAuthorize("hasAuthority('setup:edit')")
    public GoLiveResponse goLive(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.goLive(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }

    @GetMapping("/go-live/poster")
    @PreAuthorize("hasAuthority('setup:view')")
    public ResponseEntity<byte[]> poster() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"booking-poster.pdf\"").body(service.poster());
    }
}
