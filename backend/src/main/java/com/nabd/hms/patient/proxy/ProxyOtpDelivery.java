package com.nabd.hms.patient.proxy;

import com.nabd.hms.common.WhatsAppOtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * IDC-009: sends the member's OTP with the same WhatsApp sender the staff login uses. Holds the external seam, so it has no
 * transaction: the code is committed first (ProxyService.issueOtp), then sent. A failed send is a 503 and the member asks again.
 */
@Component
class ProxyOtpDelivery {

    private static final Logger log = LoggerFactory.getLogger(ProxyOtpDelivery.class);
    private final WhatsAppOtpSender sender;

    ProxyOtpDelivery(WhatsAppOtpSender sender) {
        this.sender = sender;
    }

    void send(ProxyService.Otp otp) {
        try {
            sender.send(otp.phone(), otp.code());
        } catch (RuntimeException e) {
            log.warn("proxy OTP delivery failed");
            throw new com.nabd.hms.common.ApiException(HttpStatus.SERVICE_UNAVAILABLE, "otp-send-failed", "Code not sent", "We could not send the code. Try again in a moment.");
        }
    }
}
