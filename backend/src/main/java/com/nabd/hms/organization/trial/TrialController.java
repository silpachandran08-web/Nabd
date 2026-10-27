package com.nabd.hms.organization.trial;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.organization.dpa.dto.DpaOtpChallengeResponse;
import com.nabd.hms.organization.trial.dto.TrialConvertRequest;
import com.nabd.hms.organization.trial.dto.TrialConvertResponse;
import com.nabd.hms.organization.trial.dto.TrialStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADM-004. Converting needs setup:edit AND the clinic's Owner role (403 NOT_OWNER, checked in TrialService). */
@RestController
@RequestMapping("/v1/setup/trial")
class TrialController {

    private final TrialService service;
    private final TrialConversionWorker worker;

    TrialController(TrialService service, TrialConversionWorker worker) {
        this.service = service;
        this.worker = worker;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    public TrialStatusResponse status() {
        return service.status();
    }

    @PostMapping("/convert/otp")
    @PreAuthorize("hasAuthority('setup:edit')")
    public DpaOtpChallengeResponse requestOtp(@AuthenticationPrincipal Jwt jwt) {
        return service.requestOtp(UUID.fromString(jwt.getSubject()));
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/convert")
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TrialConvertResponse convert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TrialConvertRequest req, HttpServletRequest http) {
        TrialConvertResponse out = service.convert(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req);
        worker.kick(UUID.fromString(jwt.getClaimAsString("tenantId")), out.conversionJobId()); // the service transaction has committed
        return out;
    }
}
