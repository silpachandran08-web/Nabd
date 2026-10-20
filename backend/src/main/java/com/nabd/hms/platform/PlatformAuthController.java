package com.nabd.hms.platform;

import com.nabd.hms.auth.dto.MfaVerifyRequest;
import com.nabd.hms.auth.dto.RefreshRequest;
import com.nabd.hms.auth.dto.SessionResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.platform.dto.OperatorLoginRequest;
import com.nabd.hms.platform.dto.OperatorProfileResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/platform/auth")
public class PlatformAuthController {

    private final PlatformAuthService service;

    PlatformAuthController(PlatformAuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody OperatorLoginRequest req, HttpServletRequest http) {
        Object result = service.login(req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/mfa/verify")
    public TokenPairResponse verifyMfa(@Valid @RequestBody MfaVerifyRequest req, HttpServletRequest http) {
        return service.verifyMfa(req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    /** PLT-005: forced enrolment for an operator without MFA; the platform mfa_setup token travels in the body. */
    @PostMapping("/mfa/enroll")
    public com.nabd.hms.auth.dto.MfaEnrollResponse enrollMfa(@RequestBody(required = false) com.nabd.hms.auth.dto.MfaEnrollRequest req,
                                                           HttpServletRequest http) {
        return service.enrollMfa(req == null ? null : req.setupToken(), RequestMeta.clientIp(http));
    }

    @PostMapping("/mfa/confirm")
    public ResponseEntity<Void> confirmMfa(@Valid @RequestBody com.nabd.hms.auth.dto.MfaConfirmRequest req, HttpServletRequest http) {
        service.confirmMfa(req.setupToken(), req.code(), RequestMeta.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return service.refresh(req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    @GetMapping("/me")
    public OperatorProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.getProfile(operatorId(jwt));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        service.logout(operatorId(jwt), sessionId(jwt), RequestMeta.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<SessionResponse> listSessions(@AuthenticationPrincipal Jwt jwt) {
        return service.listSessions(operatorId(jwt), sessionId(jwt));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                              HttpServletRequest http) {
        service.revokeSession(operatorId(jwt), id, RequestMeta.clientIp(http));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private UUID operatorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private UUID sessionId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }
}
