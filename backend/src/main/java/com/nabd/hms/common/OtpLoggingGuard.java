package com.nabd.hms.common;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Boot fails when the prod profile asks for OTP logging (app.whatsapp.log-otp=true). */
@Component
class OtpLoggingGuard {

    OtpLoggingGuard(Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod")) && env.getProperty("app.whatsapp.log-otp", Boolean.class, false)) {
            throw new IllegalStateException("app.whatsapp.log-otp (WHATSAPP_LOG_OTP) must be false in the prod profile");
        }
    }
}
