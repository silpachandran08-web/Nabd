package com.nabd.hms.patient.securelink;

import com.nabd.hms.common.WhatsAppOtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Non-transactional holder of the OTP provider seam, so the transactional SecureLinkService owns no external call (called after commit). */
@Component
class SecureLinkOtpDelivery {

    private static final Logger log = LoggerFactory.getLogger(SecureLinkOtpDelivery.class);
    private final WhatsAppOtpSender sender;

    SecureLinkOtpDelivery(WhatsAppOtpSender sender) {
        this.sender = sender;
    }

    void send(String phone, String code, Object linkId) {
        try {
            sender.send(phone, code);
        } catch (RuntimeException e) {
            log.warn("secure link otp delivery failed for link {}", linkId);
        }
    }
}
