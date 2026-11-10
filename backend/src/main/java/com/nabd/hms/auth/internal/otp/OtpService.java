package com.nabd.hms.auth.internal.otp;

import com.nabd.hms.auth.internal.store.StaffDirectoryRepository;
import com.nabd.hms.auth.internal.store.CredentialRepository;
import com.nabd.hms.auth.internal.store.AuthModels.Staff;
import com.nabd.hms.auth.internal.store.AuthModels.Tenant;
import com.nabd.hms.auth.internal.guard.LoginGuard;
import com.nabd.hms.auth.internal.guard.OtpOutbox;
import com.nabd.hms.auth.internal.guard.OtpSendLimiter;
import com.nabd.hms.auth.dto.OtpRequestRequest;
import com.nabd.hms.auth.dto.OtpVerifyRequest;
import com.nabd.hms.auth.dto.PinResetConfirmRequest;
import com.nabd.hms.auth.dto.PinResetRequestRequest;
import com.nabd.hms.auth.internal.login.LoginService;
import com.nabd.hms.auth.internal.token.AuthErrors;
import com.nabd.hms.auth.internal.token.TokenService;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** PLT-034 (split from AuthService): WhatsApp OTP sign-in and the NB-046 PIN reset, both delivered by the OPS-012 outbox. */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private final CredentialRepository credentials;
    private final StaffDirectoryRepository staffDir;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final OtpOutbox otpOutbox;
    private final LoginGuard guard;
    private final OtpSendLimiter otpLimiter;
    private final TokenService tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    OtpService(CredentialRepository credentials, StaffDirectoryRepository staffDir, TenantContext tenantContext, PasswordEncoder pinEncoder, OtpOutbox otpOutbox, LoginGuard guard,
               OtpSendLimiter otpLimiter, TokenService tokens, Clock clock) {
        this.credentials = credentials; this.staffDir = staffDir;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.otpOutbox = otpOutbox;
        this.guard = guard;
        this.otpLimiter = otpLimiter;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** PLT-006: no enumeration and no message pumping (see OtpSendLimiter). */
    @Transactional(noRollbackFor = ApiException.class)
    public void requestOtp(OtpRequestRequest req, String ip) {
        tokens.enforceIpRateLimit(ip);
        // PLT-006: same 202 for every outcome (unknown clinic or number, limited, sent). The per-number and per-tenant
        // limits are counted whether or not the number exists, and the WhatsApp call happens in the outbox (OPS-012
        // OtpOutbox / OtpRequestedHandler), so neither the response nor its timing shows which numbers are registered.
        Tenant tenant = staffDir.findTenantBySlug(req.tenantSlug()).filter(LoginService::active).orElse(null);
        if (tenant == null) {
            return;
        }
        tenantContext.set(tenant.id());
        if (otpLimiter.tryConsume(tenant.id(), PhoneNumbers.digits(req.mobilePhone(), tenant.region())) != OtpSendLimiter.Decision.SEND) {
            return;
        }
        Staff staff = staffDir.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            return; // PLT-006: same 202 as a real send, so the response does not reveal which numbers exist
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expiresAt = Instant.now(clock).plus(5, ChronoUnit.MINUTES);
        credentials.setWhatsAppOtp(staff.id(), OpaqueTokens.sha256Hex(code), expiresAt);
        otpOutbox.queue(tenant.id(), staff.id(), "login", code, expiresAt); // OPS-012: sent by the outbox, to the number on file
        log.info("WhatsApp OTP issued to staff {} (tenant {})", staff.id(), tenant.id());
    }

    // noRollbackFor: recordLoginAttempt()/markMobileVerified() must survive the ApiException thrown
    // on a bad code, same reasoning as login()'s lockout counter.
    @Transactional(noRollbackFor = ApiException.class)
    public Object verifyOtp(OtpVerifyRequest req, String ip, String device, String userAgent) {
        tokens.enforceIpRateLimit(ip);

        Tenant tenant = staffDir.findTenantBySlug(req.tenantSlug()).filter(LoginService::active).orElseThrow(AuthErrors::invalidCredentials);
        tenantContext.set(tenant.id());

        Staff staff = staffDir.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            throw AuthErrors.invalidCredentials();
        }

        guard.enforceLockout(staff.id());

        if (!credentials.consumeWhatsAppOtp(staff.id(), OpaqueTokens.sha256Hex(req.code()))) {
            guard.recordAttempt(tenant.id(), staff.id(), staff.email(), "otp", false);
            throw AuthErrors.invalidCredentials();
        }
        if (!staff.mfaEnabled()) {
            guard.recordAttempt(tenant.id(), staff.id(), staff.email(), "otp", true);
        }
        credentials.markMobileVerified(staff.id());
        log.info("staff {} authenticated via WhatsApp OTP, mobile verified (tenant {})", staff.id(), tenant.id());
        return tokens.afterFirstFactor(staff, ip, device, userAgent);
    }

    // ── PIN reset (NB-046) ───────────────────────────────────────────────
    // Same no-enumeration shape as requestOtp: always 202, identical timing/copy whether or not
    // the tenant/mobile resolves, so an attacker can't use this endpoint to confirm an account exists.

    @Transactional(noRollbackFor = ApiException.class)
    public void requestPinReset(PinResetRequestRequest req, String ip) {
        tokens.enforceIpRateLimit(ip);
        Tenant tenant = staffDir.findTenantBySlug(req.tenantSlug()).filter(LoginService::active).orElse(null);
        if (tenant == null) {
            return;
        }
        tenantContext.set(tenant.id());
        Staff staff = staffDir.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            return;
        }
        String token = OpaqueTokens.generate();
        Instant expiresAt = Instant.now(clock).plus(15, ChronoUnit.MINUTES);
        credentials.setPinResetToken(staff.id(), OpaqueTokens.sha256Hex(token), expiresAt);
        otpOutbox.queue(tenant.id(), staff.id(), "pin_reset", token, expiresAt); // OPS-012: sent by the outbox
        log.info("PIN reset token issued to staff {} (tenant {})", staff.id(), tenant.id());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void confirmPinReset(PinResetConfirmRequest req, String ip) {
        tokens.enforceIpRateLimit(ip);
        Tenant tenant = staffDir.findTenantBySlug(req.tenantSlug()).filter(LoginService::active).orElseThrow(AuthErrors::invalidCredentials);
        tenantContext.set(tenant.id());
        Staff staff = staffDir.findStaffByMobile(tenant.id(), tenant.region(), req.mobilePhone()).orElse(null);
        if (staff == null) {
            throw AuthErrors.invalidCredentials();
        }
        boolean reset = credentials.consumePinResetToken(staff.id(), OpaqueTokens.sha256Hex(req.token()), pinEncoder.encode(req.newPin()));
        if (!reset) {
            throw AuthErrors.invalidCredentials();
        }
        log.info("staff {} completed PIN reset (tenant {})", staff.id(), tenant.id());
    }
}
