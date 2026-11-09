package com.nabd.hms.patient.proxy;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.StepUpVerifier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** IDC-013: the clinic revokes a caregiver on the patient's verified request. The step-up (X-Step-Up-Token, asked for with
 * target = the proxy id at /v1/auth/mfa/verify) names this proxy and is used once; the reason is audited. */
@RestController
class ProxyRevokeController {

    @Schema(name = "ProxyRevokeRequest")
    record Request(@NotBlank @Size(max = 200) String reason) {
    }

    private final ProxyService service;
    private final StepUpVerifier stepUp;

    ProxyRevokeController(ProxyService service, StepUpVerifier stepUp) {
        this.service = service;
        this.stepUp = stepUp;
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/v1/patients/{id}/proxies/{proxyId}/revoke")
    @PreAuthorize("hasAuthority('patients:edit')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID proxyId, @Valid @RequestBody Request req, HttpServletRequest http,
                @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, staff, proxyId.toString());
        service.clinicRevoke(id, proxyId, staff, su.assertionId(), req.reason(), RequestMeta.clientIp(http));
    }
}
