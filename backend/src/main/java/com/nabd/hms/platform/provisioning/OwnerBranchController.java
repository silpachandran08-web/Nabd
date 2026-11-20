package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * PLT-022: the owner's branches (GET), "Add branch" (POST, 202 + a provisioning job) and retry of a job that stopped.
 * setup:view / setup:edit like the rest of owner setup; the service also requires the brand's owner account with the built-in
 * Owner role, and a step-up bound to the branch code for the POST (no separate branch permission exists in the RBAC matrix).
 */
@RestController
@RequestMapping("/v1/owner/branches")
class OwnerBranchController {

    /** No region field on purpose: the branch is created in the owner's region (region pinning). */
    record AddBranchRequest(@NotBlank @Size(max = 120) String name,
                            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,19}$") String branchCode,
                            @NotBlank @Size(max = 500) String address,
                            @NotBlank @Size(max = 64) String timezone,
                            @NotNull UUID copyFromTenantId,
                            @NotNull @Size(max = 6) List<@Pattern(regexp = "departments|charges|policies|roles|templates|hours") String> copy) {
    }

    private final OwnerBranchService service;

    OwnerBranchController(OwnerBranchService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    OwnerBranchService.BranchList list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(UUID.fromString(jwt.getSubject()));
    }

    @Idempotent
    @PostMapping
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OwnerBranchService.Started add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddBranchRequest req, HttpServletRequest http,
                                   @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        return service.start(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req.name(), req.branchCode(), req.address(),
                req.timezone(), req.copyFromTenantId(), req.copy(), stepUpToken);
    }

    @PostMapping("/jobs/{jobId}/retry")
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OwnerBranchService.Started retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId, HttpServletRequest http) {
        return service.retry(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), jobId);
    }
}
