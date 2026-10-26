package com.nabd.hms.platform.trial;

/**
 * ADM-003: delivers the sign-up OTP to a visitor who has no tenant yet. The real implementation is the comms-gateway /otp
 * endpoint for pre-tenant recipients (Needs external action: not built); until a bean exists, starting a trial answers 503
 * rather than pretending the code was sent. The code must never be logged.
 */
public interface TrialOtpSender {

    void send(String mobileE164, String code, String channel);
}
