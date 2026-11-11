package com.nabd.hms.billing.gateway;

import jakarta.validation.Valid;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** FIN-019: the owner connects the clinic's own gateway account. Connect and disconnect need a step-up token. */
@RestController
@RequestMapping("/v1/billing/gateway-accounts")
public class GatewayAccountController {

    private final GatewayAccountService service;
    private final StepUpVerifier stepUp;

    GatewayAccountController(GatewayAccountService service, StepUpVerifier stepUp) {
        this.service = service;
        this.stepUp = stepUp;
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('billing:view')")
    public GatewayAccountService.Current current() {
        return service.current();
    }

    @Idempotent
    @PostMapping
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayAccountService.AccountView connect(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GatewayAccountService.ConnectRequest req,
                                                     HttpServletRequest http, @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        stepUp.require(stepUpToken, staff);
        return service.connect(staff, RequestMeta.clientIp(http), req);
    }

    @Idempotent
    @PostMapping("/{id}/test-payment")
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GatewayAccountService.TestRun testPayment(@PathVariable UUID id) {
        return service.testPayment(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http,
                           @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        stepUp.require(stepUpToken, staff);
        service.disconnect(id, staff, RequestMeta.clientIp(http));
    }
}
