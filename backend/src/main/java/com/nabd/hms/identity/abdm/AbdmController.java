package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.StepUpVerifier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** IDC-016: the owner's ABDM switch. Off by default; turning it on verifies the HFR id first. */
@RestController
class AbdmController {

    @Schema(name = "AbdmSettingsRequest")
    record Request(@NotNull Boolean enabled, @Size(max = 40) String hfrId) {
    }

    @Schema(name = "AbdmSettings")
    record Response(boolean enabled, boolean hfrVerified, String hfrId) {
    }

    private final AbdmService service;
    private final StepUpVerifier stepUp;

    AbdmController(AbdmService service, StepUpVerifier stepUp) {
        this.service = service;
        this.stepUp = stepUp;
    }

    @GetMapping("/v1/settings/integrations/abdm")
    @PreAuthorize("hasAuthority('setup:view')")
    Response get() {
        return toResponse(service.get());
    }

    @PutMapping("/v1/settings/integrations/abdm")
    @PreAuthorize("hasAuthority('setup:edit')")
    Response put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request req, HttpServletRequest http,
                 @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        stepUp.require(stepUpToken, staff);
        return toResponse(service.put(staff, RequestMeta.clientIp(http), req.enabled(), req.hfrId()));
    }

    private static Response toResponse(AbdmSettingsService.Settings s) {
        return new Response(s.enabled(), s.hfrVerified(), s.hfrId());
    }
}
