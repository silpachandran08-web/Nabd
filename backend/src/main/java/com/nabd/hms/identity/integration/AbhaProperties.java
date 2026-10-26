package com.nabd.hms.identity.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IDC-017 app.abdm.abha.*: M1 (ABHA) endpoint paths below app.abdm.base-url. Blank = "not configured" (503), never a guess:
 * the sandbox contract is Needs external action (DEP-05), exactly as for the HFR/HPR lookups.
 */
@ConfigurationProperties(prefix = "app.abdm.abha")
public record AbhaProperties(String certPath, String loginOtpPath, String loginVerifyPath, String enrolOtpPath, String enrolPath) {

    public AbhaProperties {
        certPath = certPath == null ? "" : certPath;
        loginOtpPath = loginOtpPath == null ? "" : loginOtpPath;
        loginVerifyPath = loginVerifyPath == null ? "" : loginVerifyPath;
        enrolOtpPath = enrolOtpPath == null ? "" : enrolOtpPath;
        enrolPath = enrolPath == null ? "" : enrolPath;
    }
}
