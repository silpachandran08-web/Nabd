package com.nabd.hms.identity.abdm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nabd.hms.common.RequestMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** IDC-017: create or verify a patient's ABHA at registration. Optional; 409 abdm-disabled while the clinic's ABDM switch is off. */
@RestController
class AbhaController {

    @Schema(name = "AbhaVerifyRequest")
    record VerifyRequest(@NotBlank @Pattern(regexp = "init|confirm") String step, @Size(max = 64) String abhaNumberOrAddress,
                         @Size(max = 8) String otp, @Size(max = 100) String txnId) {
    }

    @Schema(name = "AbhaEnrolRequest")
    record EnrolRequest(@NotBlank @Pattern(regexp = "init|confirm") String step, @NotBlank @Pattern(regexp = "aadhaar|mobile") String mode,
                        @Size(max = 14) String aadhaarNumber, @Size(max = 16) String mobile, @Size(max = 8) String otp,
                        @Size(max = 100) String txnId, UUID patientId) {
        /** The Aadhaar number must not appear in any log line or exception text built from this object. */
        @Override
        public String toString() {
            return "AbhaEnrolRequest[step=" + step + ", mode=" + mode + "]";
        }
    }

    @Schema(name = "AbhaResult")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Response(String txnId, Boolean verified, String abhaLast4, String abhaAddress) {
    }

    @Schema(name = "AbhaStatus")
    record Status(boolean enabled) {
    }

    private final AbhaService service;

    AbhaController(AbhaService service) {
        this.service = service;
    }

    /** Lets the desk show the ABHA chip only when the clinic has switched ABDM on (the settings endpoint needs setup:view). */
    @GetMapping("/v1/abha/status")
    @PreAuthorize("hasAuthority('patients:edit')")
    Status status() {
        return new Status(service.enabled());
    }

    @PostMapping("/v1/patients/{id}/abha/verify")
    @PreAuthorize("hasAuthority('patients:edit')")
    Response verify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody VerifyRequest req, HttpServletRequest http) {
        return toResponse(service.verify(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), id, req.step(), req.abhaNumberOrAddress(), req.otp(), req.txnId()));
    }

    @PostMapping("/v1/abha/enrol")
    @PreAuthorize("hasAuthority('patients:edit')")
    Response enrol(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EnrolRequest req, HttpServletRequest http) {
        return toResponse(service.enrol(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req.patientId(), req.step(), req.mode(),
                req.aadhaarNumber(), req.mobile(), req.otp(), req.txnId()));
    }

    private static Response toResponse(AbhaService.Result r) {
        return new Response(r.txnId(), r.verified(), r.abhaLast4(), r.abhaAddress());
    }
}
