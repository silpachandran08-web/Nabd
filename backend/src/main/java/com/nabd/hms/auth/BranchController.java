package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.BranchListResponse;
import com.nabd.hms.auth.dto.BranchSelectRequest;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.internal.branch.BranchService;
import com.nabd.hms.auth.internal.store.BranchRepository.LinkCandidate;
import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * PLT-023: the branch list and choice of the signed-in person, and the owner's staff links across branches.
 * POST /v1/auth/branches/select is open in SecurityConfig like the other sign-in steps: it takes either the caller's access
 * token (a switch; the resource server has validated it) or, with no bearer, the brand_select token of a sign-in in the body.
 */
@RestController
class BranchController {

    record LinkRequest(@NotNull @Size(min = 2, max = 10) List<@NotNull UUID> staffIds) {
    }

    record LinkResponse(UUID brandLoginId) {
    }

    private final BranchService service;

    BranchController(BranchService service) {
        this.service = service;
    }

    @GetMapping("/v1/auth/branches")
    BranchListResponse list(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @AuthenticationPrincipal Jwt jwt) {
        return service.list(tenantId, staffId, Boolean.TRUE.equals(jwt.getClaim("reportsOnly")));
    }

    @PostMapping("/v1/auth/branches/select")
    TokenPairResponse select(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody BranchSelectRequest req, HttpServletRequest http) {
        return service.select(caller, req, RequestMeta.clientIp(http), RequestMeta.deviceLabel(http), RequestMeta.userAgent(http));
    }

    /** For the owner's link form: the same person's staff accounts in the brand's other branches. Empty for anyone but the brand's owner. */
    @GetMapping("/v1/owner/branches/staff-links/candidates")
    @PreAuthorize("hasAuthority('staff:edit')")
    List<LinkCandidate> candidates(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @RequestParam UUID staffIdToLink) {
        return service.linkCandidates(tenantId, staffId, staffIdToLink);
    }

    /** staff:edit like the rest of staff administration; the service also requires the brand's owner account and a step-up
     * confirmation bound to exactly these staff rows (target "staff-link:" + the sorted ids, comma separated). */
    @Idempotent
    @PostMapping("/v1/owner/branches/staff-links")
    @PreAuthorize("hasAuthority('staff:edit')")
    @ResponseStatus(HttpStatus.CREATED)
    LinkResponse link(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody LinkRequest req, HttpServletRequest http,
                      @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        return new LinkResponse(service.link(tenantId, staffId, req.staffIds(), stepUpToken, RequestMeta.clientIp(http)));
    }
}
