package com.nabd.hms.auth.internal.login;

import com.nabd.hms.auth.internal.store.StaffDirectoryRepository;
import com.nabd.hms.auth.internal.store.CredentialRepository;
import com.nabd.hms.auth.internal.store.AuthModels.Staff;
import com.nabd.hms.auth.internal.store.AuthModels.Tenant;
import com.nabd.hms.auth.internal.guard.LoginGuard;
import com.nabd.hms.auth.dto.LoginRequest;
import com.nabd.hms.auth.internal.token.AuthErrors;
import com.nabd.hms.auth.internal.token.TokenService;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PLT-034 (split from AuthService): email + PIN sign-in, the first factor that ends in MFA or a session. */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final CredentialRepository credentials;
    private final StaffDirectoryRepository staffDir;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final LoginGuard guard;
    private final TokenService tokens;

    LoginService(CredentialRepository credentials, StaffDirectoryRepository staffDir, TenantContext tenantContext, PasswordEncoder pinEncoder, LoginGuard guard, TokenService tokens) {
        this.credentials = credentials; this.staffDir = staffDir;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.guard = guard;
        this.tokens = tokens;
    }

    /** Offboarded and suspended clinics sign in nobody (shared with the OTP and PIN-reset flows). */
    public static boolean active(Tenant t) {
        return !"offboarded".equals(t.status()) && !"suspended".equals(t.status());
    }

    // noRollbackFor: recordLoginAttempt() below is the lockout counter's only durable state, and
    // every failing branch writes one immediately before throwing — Spring's default rollback-on-
    // RuntimeException would silently undo that write and permanently disable lockout.
    @Transactional(noRollbackFor = ApiException.class)
    public Object login(LoginRequest req, String ip, String device, String userAgent) {
        tokens.enforceIpRateLimit(ip);

        Tenant tenant = staffDir.findTenantBySlug(req.tenantSlug()).filter(LoginService::active).orElseThrow(AuthErrors::invalidCredentials);

        tenantContext.set(tenant.id());
        String email = req.email().toLowerCase();

        Staff staff = staffDir.findStaffByEmail(tenant.id(), email).orElse(null);
        if (staff == null || !"active".equals(staff.status())) {
            credentials.recordLoginAttempt(tenant.id(), staff == null ? null : staff.id(), email, ip, false);
            throw AuthErrors.invalidCredentials();
        }

        // A still-locked hit records nothing (a client polling during the lock would otherwise renew it forever);
        // see LoginGuard.enforceLockout, shared by every credential factor.
        guard.enforceLockout(staff.id());

        if (staff.pinHash() == null || !pinEncoder.matches(req.pin(), staff.pinHash())) {
            credentials.recordLoginAttempt(tenant.id(), staff.id(), email, ip, false);
            throw AuthErrors.invalidCredentials();
        }
        if (!staff.mfaEnabled()) { // with MFA the success is recorded after the second factor, so PIN logins cannot reset TOTP failures
            credentials.recordLoginAttempt(tenant.id(), staff.id(), email, ip, true);
        }
        log.info("staff {} authenticated via PIN (tenant {})", staff.id(), tenant.id());
        return tokens.afterFirstFactor(staff, ip, device, userAgent);
    }
}
