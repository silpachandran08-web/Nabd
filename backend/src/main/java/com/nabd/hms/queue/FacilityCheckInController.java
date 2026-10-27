package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * ACC-033: staff side of self check-in: the clinic's settings, the door QR poster and the recent attempts.
 * The story names scheduling:configure; no such authority exists in the grant model, so setup:edit (the clinic-settings
 * authority) guards the settings and the poster, queue:view the attempts list.
 */
@RestController
class FacilityCheckInController {

    private final SelfCheckInService service;

    FacilityCheckInController(SelfCheckInService service) {
        this.service = service;
    }

    @GetMapping("/v1/facility/checkin-config")
    @PreAuthorize("hasAuthority('setup:edit')")
    SelfCheckInService.Config config(@AuthenticationPrincipal Jwt jwt) {
        return service.config(tenant(jwt));
    }

    @PutMapping("/v1/facility/checkin-config")
    @PreAuthorize("hasAuthority('setup:edit')")
    SelfCheckInService.Config save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SelfCheckInService.Config body) {
        return service.saveConfig(tenant(jwt), UUID.fromString(jwt.getSubject()), body);
    }

    @GetMapping("/v1/facility/checkin-qr")
    @PreAuthorize("hasAuthority('setup:edit')")
    ResponseEntity<byte[]> poster(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "png") String format) {
        if (!format.equals("png") && !format.equals("pdf")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "format must be png or pdf.");
        }
        UUID tid = tenant(jwt);
        boolean[][] qr = Qr.encode(service.posterLink(tid));
        String code = service.config(tid).checkinCode();
        return format.equals("png")
                ? ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control", "no-store").body(Qr.png(qr, 10))
                : ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Cache-Control", "no-store")
                .header("Content-Disposition", "inline; filename=\"checkin-poster.pdf\"").body(Qr.pdf(qr, "Scan to check in", "Or send: CHECKIN " + code));
    }

    @GetMapping("/v1/facility/checkin-attempts")
    @PreAuthorize("hasAuthority('queue:view')")
    List<SelfCheckInRepository.Attempt> attempts(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit) {
        return service.recent(tenant(jwt), limit);
    }

    private static UUID tenant(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }
}
