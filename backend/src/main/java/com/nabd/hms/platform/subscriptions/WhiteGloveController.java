package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
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

    private static final Logger log = LoggerFactory.getLogger(WhiteGloveController.class);

    private final WhiteGloveService service;
    private final TenantScope tenantScope;

    WhiteGloveController(WhiteGloveService service, TenantScope tenantScope) {
        this.service = service;
        this.tenantScope = tenantScope;
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
        try {
            return service.book(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req.preferredSlots(), req.notes(), stepUpToken);
        } catch (ApiException e) {
            if (WhiteGloveService.NOT_OWNER.equals(e.title())) audit(jwt, http, "not owner"); // after the refused transaction rolled back
            throw e;
        }
    }

    /** T1-BUG-3: a role without the endpoint's permission gets the security chain's 403, after an audit row naming who tried (ids only). */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException denial, HttpServletRequest http) {
        if (SecurityContextHolder.getContext().getAuthentication() != null && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Jwt jwt) {
            audit(jwt, http, "POST".equals(http.getMethod()) ? "missing setup:edit" : "missing setup:view");
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission to do this.");
        p.setType(URI.create("https://api.nabd.health/problems/forbidden"));
        p.setTitle("Forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    private void audit(Jwt jwt, HttpServletRequest http, String reason) {
        if (jwt.getClaimAsString("tenantId") == null) return;
        try {
            tenantScope.run(UUID.fromString(jwt.getClaimAsString("tenantId")), () -> service.denied(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), reason));
        } catch (RuntimeException e) {
            log.warn("white-glove refusal not audited ({})", e.getClass().getSimpleName());
        }
    }
}
