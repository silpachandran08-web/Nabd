package com.nabd.hms.specialty;

import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** CLN-042: the profile registry with this clinic's entitlement, a staff member's profiles, and the caller's workspace config. */
@RestController
public class SpecialtyController {

    public record AssignRequest(@NotNull @Pattern(regexp = "^[A-Z]{2,8}$") String primary,
                                @Pattern(regexp = "^[A-Z]{2,8}$") String secondary) {
    }

    private final SpecialtyService service;

    SpecialtyController(SpecialtyService service) {
        this.service = service;
    }

    @GetMapping("/v1/specialty/profiles")
    @PreAuthorize("hasAuthority('staff:view')")
    public SpecialtyService.ProfileList profiles() {
        return service.profiles();
    }

    @GetMapping("/v1/specialty/me")
    @PreAuthorize("isAuthenticated()")
    public SpecialtyService.Me me(@CurrentTenant UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return service.me(tenantId, UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/v1/staff/{staffId}/specialty-profiles")
    @PreAuthorize("hasAuthority('staff:view')")
    public SpecialtyService.Assignment get(@CurrentTenant UUID tenantId, @PathVariable UUID staffId) {
        return service.assignment(tenantId, staffId);
    }

    @PutMapping("/v1/staff/{staffId}/specialty-profiles")
    @PreAuthorize("hasAuthority('staff:edit')")
    public SpecialtyService.Assignment put(@CurrentTenant UUID tenantId, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID staffId,
                                           @Valid @RequestBody AssignRequest req, HttpServletRequest http) {
        return service.assign(tenantId, UUID.fromString(jwt.getSubject()), staffId, req.primary(), req.secondary(), RequestMeta.clientIp(http));
    }
}
