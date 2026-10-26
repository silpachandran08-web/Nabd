package com.nabd.hms.staff;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import com.nabd.hms.staff.dto.InviteResendResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADM-018: owner actions on the staff screen (resend an invite, send an MFA enrolment reminder). */
@RestController
@RequestMapping("/v1/staff")
class StaffAccessController {

    private final StaffAccessService service;

    StaffAccessController(StaffAccessService service) {
        this.service = service;
    }

    @PostMapping("/{id}/invite/resend")
    @Idempotent
    @PreAuthorize("hasAuthority('staff:create')")
    InviteResendResponse resend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        return service.resendInvite(id, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }

    @PostMapping("/{id}/mfa/remind")
    @Idempotent
    @PreAuthorize("hasAuthority('staff:edit')")
    ResponseEntity<Void> remind(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        service.remindMfa(id, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
        return ResponseEntity.noContent().build();
    }
}
