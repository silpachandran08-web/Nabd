package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.BreakGlassActivateRequest;
import com.nabd.hms.auth.dto.BreakGlassResponse;
import com.nabd.hms.auth.dto.LoginRequest;
import com.nabd.hms.auth.dto.MfaConfirmRequest;
import com.nabd.hms.auth.dto.MfaConfirmResponse;
import com.nabd.hms.auth.dto.MfaEnrollResponse;
import com.nabd.hms.auth.dto.MfaVerifyRequest;
import com.nabd.hms.auth.dto.OtpRequestRequest;
import com.nabd.hms.auth.dto.OtpVerifyRequest;
import com.nabd.hms.auth.dto.PinResetConfirmRequest;
import com.nabd.hms.auth.dto.PinResetRequestRequest;
import com.nabd.hms.auth.dto.RefreshRequest;
import com.nabd.hms.auth.dto.SessionResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.dto.UnlockRequest;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        Object result = authService.login(req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(@Valid @RequestBody OtpRequestRequest req, HttpServletRequest http) {
        authService.requestOtp(req, RequestMeta.clientIp(http));
        return ResponseEntity.accepted().build(); // always 202 — same tenant/mobile no-enumeration shape as password-reset
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<Object> verifyOtp(@Valid @RequestBody OtpVerifyRequest req, HttpServletRequest http) {
        Object result = authService.verifyOtp(req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/mfa/verify")
    public Object verifyMfa(@Valid @RequestBody MfaVerifyRequest req, HttpServletRequest http,
                             @AuthenticationPrincipal Jwt callerJwt) {
        // an Authorization header was sent and validated -> this is an already-logged-in caller
        // re-proving MFA for a step-up token, not someone completing a fresh login challenge
        if (callerJwt != null) {
            return authService.issueStepUpToken(callerJwt, req.code());
        }
        return authService.verifyMfa(req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return authService.refresh(req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        authService.logout(tenantId(jwt), sessionId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<SessionResponse> listSessions(@AuthenticationPrincipal Jwt jwt) {
        return authService.listSessions(tenantId(jwt), UUID.fromString(jwt.getSubject()), sessionId(jwt));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        authService.revokeSession(tenantId(jwt), UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/mfa/enroll")
    public MfaEnrollResponse enrollMfa(@AuthenticationPrincipal Jwt jwt) {
        return authService.enrollMfa(jwt);
    }

    @PostMapping("/mfa/confirm")
    public MfaConfirmResponse confirmMfa(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MfaConfirmRequest req) {
        return authService.confirmMfa(jwt, req);
    }

    @PostMapping("/pin/reset-request")
    public ResponseEntity<Void> requestPinReset(@Valid @RequestBody PinResetRequestRequest req, HttpServletRequest http) {
        authService.requestPinReset(req, RequestMeta.clientIp(http));
        return ResponseEntity.accepted().build(); // always 202 — no user enumeration, mirrors /otp/request
    }

    @PostMapping("/pin/reset-confirm")
    public ResponseEntity<Void> confirmPinReset(@Valid @RequestBody PinResetConfirmRequest req, HttpServletRequest http) {
        authService.confirmPinReset(req, RequestMeta.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unlock")
    public ResponseEntity<Void> unlock(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UnlockRequest req) {
        authService.unlock(jwt, req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/break-glass/activate")
    public TokenPairResponse activateBreakGlass(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BreakGlassActivateRequest req,
                                                 HttpServletRequest http) {
        return authService.activateBreakGlass(jwt, req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    @PostMapping("/break-glass/{id}/deactivate")
    public ResponseEntity<Void> deactivateBreakGlass(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        authService.deactivateBreakGlass(jwt, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/break-glass/active")
    @PreAuthorize("hasAuthority('staff:view')")
    public List<BreakGlassResponse> activeBreakGlass(@AuthenticationPrincipal Jwt jwt) {
        return authService.listActiveBreakGlass(tenantId(jwt));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID sessionId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }
}
