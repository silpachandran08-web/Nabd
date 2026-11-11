package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADM-006: the wizard's "Have us set it up" card. Reads need setup:view; booking needs setup:edit, the built-in Owner role and a
 * step-up bound to "white-glove:book" (the spec's plan:manage does not exist in the RBAC matrix; same gate as ADM-037's plan change).
 */
@RestController
@RequestMapping("/v1/setup/white-glove")
class WhiteGloveController {

    record BookRequest(@NotEmpty @Size(max = 3) List<Instant> preferredSlots, @Size(max = 500) String notes) {
    }

    private final WhiteGloveService service;

    WhiteGloveController(WhiteGloveService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    WhiteGloveService.OwnerView view() {
        return service.view();
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.CREATED)
    WhiteGloveService.Booked book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookRequest req, HttpServletRequest http,
                                  @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        return service.book(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req.preferredSlots(), req.notes(), stepUpToken);
    }
}
