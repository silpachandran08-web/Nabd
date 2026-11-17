package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.BreakGlassReadsResponse;
import com.nabd.hms.auth.dto.BreakGlassReviewQueueResponse;
import com.nabd.hms.auth.dto.BreakGlassReviewRequest;
import com.nabd.hms.auth.dto.BreakGlassReviewResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import com.nabd.hms.shared.phiread.PhiRead;
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

/** PLT-013: the owner's break-glass review queue (Compliance screen). The Owner role holds compliance:view/approve and staff:edit. */
@Validated
@RestController
@RequestMapping("/v1/break-glass/grants")
class BreakGlassReviewController {

    private final BreakGlassReviewService service;

    BreakGlassReviewController(BreakGlassReviewService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('compliance:view', 'staff:edit')")
    BreakGlassReviewQueueResponse queue(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.queue(UUID.fromString(jwt.getClaimAsString("tenantId")), status, cursor, limit);
    }

    // the owner seeing these patients' names is itself a read: recorded per patient (directory-level data, no clinical content)
    @GetMapping("/{id}/reads")
    @PreAuthorize("hasAuthority('compliance:view')")
    @PhiRead(value = "break_glass_review_list", purpose = "directory")
    BreakGlassReadsResponse reads(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.reads(UUID.fromString(jwt.getClaimAsString("tenantId")), id);
    }

    // removing the holder's clinical:breakGlass additionally needs staff:edit (checked in the service)
    @Idempotent
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('compliance:approve')")
    BreakGlassReviewResponse review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody BreakGlassReviewRequest req,
                                    HttpServletRequest http) {
        return service.review(jwt, id, req, RequestMeta.clientIp(http));
    }
}
