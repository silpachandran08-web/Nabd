package com.nabd.hms.organization.offboarding;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.organization.export.DataExportService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * ADM-016: the owner's deletion request (setup:export, the built-in Owner role checked on the server, and a step-up used once:
 * target "offboarding" to ask, "offboarding-cancel:{id}" to cancel that request), and the signed certificate: operators read
 * it by id (tenant_detail:view), anyone holding the link reads it by id + SHA-256 of the signed document. No endpoint erases
 * anything: only {@link OffboardingJob} does, after the cooling-off, for the clinic the request belongs to.
 */
@RestController
class OffboardingController {

    @Schema(name = "OffboardingRequest")
    record RequestBodyDto(UUID exportId, Boolean waiveExport, @NotBlank @Size(max = 500) String reason) {
    }

    private static final String SHA256 = "^[0-9a-f]{64}$";

    private final OffboardingService service;
    private final DataExportService owners;
    private final StepUpVerifier stepUp;

    OffboardingController(OffboardingService service, DataExportService owners, StepUpVerifier stepUp) {
        this.service = service;
        this.owners = owners;
        this.stepUp = stepUp;
    }

    @GetMapping("/v1/setup/offboarding")
    @PreAuthorize("hasAuthority('setup:export')")
    OffboardingService.Status status(@AuthenticationPrincipal Jwt jwt) {
        owners.requireOwner(UUID.fromString(jwt.getSubject()));
        return service.status();
    }

    @PostMapping("/v1/setup/offboarding")
    @PreAuthorize("hasAuthority('setup:export')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OffboardingService.Requested request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RequestBodyDto b, HttpServletRequest http,
                                         @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        owners.requireOwner(staff);
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, staff, OffboardingService.STEP_UP_TARGET);
        return service.request(staff, su.assertionId(), b.exportId(), Boolean.TRUE.equals(b.waiveExport()), b.reason(), RequestMeta.clientIp(http));
    }

    @PostMapping("/v1/setup/offboarding/{id}/cancel")
    @PreAuthorize("hasAuthority('setup:export')")
    OffboardingService.Cancelled cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http,
                                        @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        owners.requireOwner(staff);
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, staff, OffboardingService.CANCEL_STEP_UP_TARGET + id);
        return service.cancel(staff, su.assertionId(), id, RequestMeta.clientIp(http));
    }

    // ---------------------------------------------------------------- the certificate

    @GetMapping("/v1/platform/deletion-certificates/{id}")
    @PreAuthorize("hasAuthority('tenant_detail:view')")
    OffboardingService.Certificate forOperator(@PathVariable UUID id) {
        return service.certificate(id, null);
    }

    /** Nabd's public keys for checking a certificate's signature (no private material). */
    @GetMapping("/v1/public/deletion-certificates/keys")
    ResponseEntity<Map<String, Object>> keys() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic()).body(service.publicKeys());
    }

    @GetMapping("/v1/public/deletion-certificates/{id}/{sha256}")
    ResponseEntity<OffboardingService.Certificate> forHolder(@PathVariable UUID id, @PathVariable @Pattern(regexp = SHA256) String sha256) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.certificate(id, sha256));
    }

    @GetMapping("/v1/public/deletion-certificates/{id}/{sha256}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID id, @PathVariable @Pattern(regexp = SHA256) String sha256) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"nabd-deletion-certificate.pdf\"").body(service.pdf(service.certificate(id, sha256)));
    }
}
