package com.nabd.hms.auth.dto;

/** setupToken: the typ=mfa_setup token from login (omitted when an access session re-enrols). currentCode: the live TOTP, required to re-enrol when MFA is already on (alternative: X-Step-Up-Token header). */
public record MfaEnrollRequest(String setupToken, String currentCode) {
}
