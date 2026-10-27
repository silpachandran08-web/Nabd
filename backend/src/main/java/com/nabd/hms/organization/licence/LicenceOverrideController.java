package com.nabd.hms.organization.licence;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.organization.setup.dto.LicenceResponse;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * ADM-032: the owner lets an expired clinician licence keep the doctor bookable online for a limited time (at most 30 days) while the
 * renewal is in progress. Owner only, fresh MFA confirmation (X-Step-Up-Token), reason required, audited. It does not touch
 * prescription signing: that has its own override (CLN-005).
 */
@RestController
class LicenceOverrideController {

    @Schema(name = "LicenceOverrideRequest")
    record Request(@NotNull LocalDate until, @NotBlank @Size(max = 200) String reason) {
    }

    private final LicenceOverrideRunner runner;
    private final StepUpVerifier stepUp;

    LicenceOverrideController(LicenceOverrideRunner runner, StepUpVerifier stepUp) {
        this.runner = runner;
        this.stepUp = stepUp;
    }

    @Idempotent
    @PostMapping("/v1/setup/licences/{id}/override")
    @PreAuthorize("hasAuthority('setup:edit')")
    LicenceResponse override(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Request req, HttpServletRequest http,
                             @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("owner"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-only", "Owner only", "Only the clinic owner can apply a licence override.");
        }
        UUID staff = UUID.fromString(jwt.getSubject());
        stepUp.require(stepUpToken, staff);
        return runner.run(UUID.fromString(jwt.getClaimAsString("tenantId")), id, staff, RequestMeta.clientIp(http), req.until(), req.reason());
    }
}
