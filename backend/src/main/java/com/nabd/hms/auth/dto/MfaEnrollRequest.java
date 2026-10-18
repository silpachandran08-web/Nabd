package com.nabd.hms.auth.dto;

/** setupToken: the typ=mfa_setup token from login; omitted when an access session re-enrols. */
public record MfaEnrollRequest(String setupToken) {
}
