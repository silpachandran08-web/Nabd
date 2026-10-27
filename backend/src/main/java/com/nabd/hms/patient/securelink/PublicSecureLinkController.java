package com.nabd.hms.patient.securelink;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.TenantScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * IDC-015: the two public calls behind a link. The token names the clinic, so no tenant header is needed. An unknown or malformed
 * token answers exactly like an expired one (410 link-expired). Responses are never cached.
 */
@RestController
class PublicSecureLinkController {

    @Schema(name = "SecureLinkOpenRequest")
    record OpenRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp) {
    }

    @Schema(name = "SecureLinkOpenResponse")
    record OpenResponse(String fileUrl, String mime, String title) {
    }

    private final TenantScope tenantScope;
    private final SecureLinkService service;

    PublicSecureLinkController(TenantScope tenantScope, SecureLinkService service) {
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @PostMapping("/v1/public/links/{token}/otp")
    ResponseEntity<Void> otp(@PathVariable String token, HttpServletRequest http) {
        UUID tenant = tenant(token);
        SecureLinkService.Result<Void> r = tenantScope.run(tenant, () -> service.sendOtp(token, RequestMeta.clientIp(http), RequestMeta.userAgent(http)));
        refuse(r.refusal());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/v1/public/links/{token}/open")
    ResponseEntity<OpenResponse> open(@PathVariable String token, @Valid @RequestBody OpenRequest req, HttpServletRequest http) {
        UUID tenant = tenant(token);
        SecureLinkService.Result<SecureLinkService.Opened> r = tenantScope.run(tenant, () -> service.open(token, req.otp(), RequestMeta.clientIp(http), RequestMeta.userAgent(http)));
        refuse(r.refusal());
        SecureLinkService.Opened o = r.value();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OpenResponse(o.fileUrl(), o.mime(), o.title()));
    }

    private static UUID tenant(String token) {
        Optional<UUID> t = SecureLinkService.tenantOf(token);
        return t.orElseThrow(() -> expired());
    }

    private static void refuse(SecureLinkService.Refusal r) {
        if (r == null) {
            return;
        }
        throw switch (r) {
            case EXPIRED -> expired();
            case LOCKED -> new ApiException(HttpStatus.LOCKED, "link-locked", "Link locked", "Too many wrong codes. Ask the clinic to send a new link.");
            case RATE_LIMITED -> new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp-rate-limited", "Too many codes requested", "Please wait before asking for another code.");
            case OTP_INVALID -> new ApiException(HttpStatus.UNAUTHORIZED, "otp-invalid", "Code not accepted", "That code is wrong or has expired.");
        };
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.GONE, "link-expired", "Link expired", "This link has expired. Ask the clinic for a new one.");
    }
}
