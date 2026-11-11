package com.nabd.hms.auth.internal.token;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;

/** PLT-034: the auth problems shared by the login, otp, mfa and session services (same type/title/detail as before the split). */
public final class AuthErrors {
    private AuthErrors() {
    }

    public static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
                "Email/mobile or credential is incorrect.");
    }

    public static ApiException mfaFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "mfa-failed", "MFA verification failed",
                "The code is incorrect or the challenge has expired.");
    }

    public static ApiException challengeInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "challenge-invalid", "Challenge invalid",
                "The sign-in challenge is invalid or has expired.");
    }

    public static ApiException tenantInactive(HttpStatus status) {
        return new ApiException(status, "TENANT_INACTIVE", "Clinic not active",
                "This clinic is suspended or closed; contact the clinic owner.");
    }

    public static ApiException sessionMaxAge() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_MAX_AGE", "Session too old",
                "Sessions last one shift; sign in again.");
    }

    public static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid-refresh-token", "Invalid refresh token",
                "Session is no longer valid; sign in again.");
    }
}
