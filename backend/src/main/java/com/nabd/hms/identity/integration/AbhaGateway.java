package com.nabd.hms.identity.integration;

/**
 * IDC-017: the ABDM M1 (ABHA) operations. Only identity.abdm.AbhaService calls it, after the clinic's ABDM switch is checked.
 * An Aadhaar number passed here lives in memory for the one call that encrypts and forwards it; it is never stored or logged.
 * Failures: ApiException 422 abha-invalid (ABDM refused the id or OTP), 503 abdm-unavailable / abdm-not-configured.
 */
public interface AbhaGateway {

    record Profile(String abhaNumber, String abhaAddress, String name, String dob, String gender, String mobile) {
    }

    enum EnrolMode { AADHAAR, MOBILE }

    /** Sends a login OTP to the mobile registered with this ABHA number or address; returns ABDM's transaction id. */
    String loginOtp(String abhaNumberOrAddress);

    Profile loginConfirm(String txnId, String otp);

    /** AADHAAR: identifier is the Aadhaar number (ABDM sends the OTP to the Aadhaar-linked mobile). MOBILE: identifier is the mobile. */
    String enrolOtp(EnrolMode mode, String identifier);

    /** mobile is the contact to attach to the new ABHA (Aadhaar mode); ignored for MOBILE mode. */
    Profile enrolConfirm(EnrolMode mode, String txnId, String otp, String mobile);
}
