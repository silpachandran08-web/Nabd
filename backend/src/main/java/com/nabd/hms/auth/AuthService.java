package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.LoginRequest;
import com.nabd.hms.auth.dto.MfaConfirmRequest;
import com.nabd.hms.auth.dto.MfaConfirmResponse;
import com.nabd.hms.auth.dto.MfaEnrollResponse;
import com.nabd.hms.auth.dto.MfaVerifyRequest;
import com.nabd.hms.auth.dto.OtpRequestRequest;
import com.nabd.hms.auth.dto.OtpVerifyRequest;
import com.nabd.hms.auth.dto.PinResetConfirmRequest;
import com.nabd.hms.auth.dto.PinResetRequestRequest;
import com.nabd.hms.auth.dto.RecoveryVerifyRequest;
import com.nabd.hms.auth.dto.RecoveryVerifyResponse;
import com.nabd.hms.auth.dto.RefreshRequest;
import com.nabd.hms.auth.dto.SessionResponse;
import com.nabd.hms.auth.dto.StepUpTokenResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.auth.dto.UnlockRequest;
import com.nabd.hms.auth.internal.login.LoginService;
import com.nabd.hms.auth.internal.mfa.MfaService;
import com.nabd.hms.auth.internal.otp.OtpService;
import com.nabd.hms.auth.internal.session.SessionService;
import com.nabd.hms.auth.internal.token.StepUpTokenService;
import com.nabd.hms.auth.internal.token.TokenService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The auth module's entry point for controllers and other modules (OwnerService, StaffController). PLT-034 split the work
 * by responsibility into auth.internal.{login, otp, mfa, session, token}; each method here delegates unchanged, and each
 * sub-service carries its own transaction (this facade opens none). Break-glass stays in BreakGlassService (PLT-012).
 */
@Service
public class AuthService {

    private final LoginService login;
    private final OtpService otp;
    private final MfaService mfa;
    private final SessionService sessions;
    private final StepUpTokenService stepUp;
    private final TokenService tokens;

    AuthService(LoginService login, OtpService otp, MfaService mfa, SessionService sessions, StepUpTokenService stepUp,
                TokenService tokens) {
        this.login = login;
        this.otp = otp;
        this.mfa = mfa;
        this.sessions = sessions;
        this.stepUp = stepUp;
        this.tokens = tokens;
    }

    public Object login(LoginRequest req, String ip, String device, String userAgent) {
        return login.login(req, ip, device, userAgent);
    }

    public void requestOtp(OtpRequestRequest req, String ip) {
        otp.requestOtp(req, ip);
    }

    public Object verifyOtp(OtpVerifyRequest req, String ip, String device, String userAgent) {
        return otp.verifyOtp(req, ip, device, userAgent);
    }

    public void requestPinReset(PinResetRequestRequest req, String ip) {
        otp.requestPinReset(req, ip);
    }

    public void confirmPinReset(PinResetConfirmRequest req, String ip) {
        otp.confirmPinReset(req, ip);
    }

    public TokenPairResponse verifyMfa(MfaVerifyRequest req, String ip, String device, String userAgent) {
        return mfa.verifyMfa(req, ip, device, userAgent);
    }

    public MfaEnrollResponse enrollMfa(Jwt callerJwt, String currentCode, String stepUpToken) {
        return mfa.enrollMfa(callerJwt, currentCode, stepUpToken);
    }

    public MfaConfirmResponse confirmMfa(Jwt callerJwt, MfaConfirmRequest req) {
        return mfa.confirmMfa(callerJwt, req);
    }

    public RecoveryVerifyResponse recoveryVerify(RecoveryVerifyRequest req, String ip, String device, String userAgent) {
        return mfa.recoveryVerify(req, ip, device, userAgent);
    }

    public StepUpTokenResponse issueStepUpToken(Jwt callerJwt, String code, String target) {
        return stepUp.issueStepUpToken(callerJwt, code, target);
    }

    public StepUpTokenResponse issuePinAssertion(Jwt callerJwt, String pin, UUID approvalId) {
        return stepUp.issuePinAssertion(callerJwt, pin, approvalId);
    }

    public void unlock(Jwt callerJwt, UnlockRequest req) {
        sessions.unlock(callerJwt, req);
    }

    public Object sessionAfterInviteAccept(UUID tenantId, UUID staffId, String ip, String device, String userAgent) {
        return sessions.sessionAfterInviteAccept(tenantId, staffId, ip, device, userAgent);
    }

    public TokenPairResponse issueTokensForStaff(UUID tenantId, UUID staffId, String ip, String device, String userAgent) {
        return sessions.issueTokensForStaff(tenantId, staffId, ip, device, userAgent);
    }

    public TokenPairResponse refresh(RefreshRequest req, String ip, String device, String userAgent) {
        return sessions.refresh(req, ip, device, userAgent);
    }

    public void logout(UUID tenantId, UUID sessionId) {
        sessions.logout(tenantId, sessionId);
    }

    public List<SessionResponse> listSessions(UUID tenantId, UUID staffId, UUID currentSessionId) {
        return sessions.listSessions(tenantId, staffId, currentSessionId);
    }

    public void revokeSession(UUID tenantId, UUID staffId, UUID sessionId) {
        sessions.revokeSession(tenantId, staffId, sessionId);
    }

    /** PLT-012: bg != null mints the read-only emergency token for that grant (see BreakGlassService). */
    TokenPairResponse mintTokenPair(AuthModels.Staff staff, UUID familyId, String ip, String device, String userAgent, BreakGlassSession bg) {
        return tokens.mintTokenPair(staff, familyId, ip, device, userAgent, bg);
    }
}
