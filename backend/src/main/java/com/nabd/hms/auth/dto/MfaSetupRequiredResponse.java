package com.nabd.hms.auth.dto;

/** NB-042: returned from login instead of a challenge/token pair when policy mandates MFA and this
 * staff member hasn't enrolled yet — setupToken authorizes only /auth/mfa/enroll and /mfa/confirm. */
public record MfaSetupRequiredResponse(String setupToken, long expiresIn) {
}
