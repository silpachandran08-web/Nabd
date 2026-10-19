package com.nabd.hms.staff;

import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** CLN-005 / PLT-016: iam is the single writer of staff.is_clinician and the licence fields the signing rule reads. */
@RestController
@RequestMapping("/v1/staff/{id}/clinical-licence")
public class StaffLicenceController {

    public record LicenceResponse(UUID staffId, boolean isClinician, boolean hasRegistrationNo, LocalDate licenceExpiresOn,
                                  Instant licenceOverrideUntil) {
    }

    public record LicenceRequest(@NotNull Boolean isClinician, LocalDate licenceExpiresOn) {
    }

    public record OverrideRequest(@NotNull Instant until) {
    }

    private final StaffLicenceService service;

    StaffLicenceController(StaffLicenceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:view')")
    public LicenceResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(tenantId(jwt), id);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('staff:edit')")
    public LicenceResponse put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody LicenceRequest req,
                               HttpServletRequest http) {
        return service.update(tenantId(jwt), UUID.fromString(jwt.getSubject()), id, req, RequestMeta.clientIp(http));
    }

    @com.nabd.hms.shared.idempotency.Idempotent
    @PostMapping("/override")
    @PreAuthorize("hasAuthority('staff:edit')")
    public LicenceResponse override(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OverrideRequest req,
                                    HttpServletRequest http) {
        return service.grantOverride(tenantId(jwt), UUID.fromString(jwt.getSubject()), id, req.until(), RequestMeta.clientIp(http));
    }

    private static UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }
}
