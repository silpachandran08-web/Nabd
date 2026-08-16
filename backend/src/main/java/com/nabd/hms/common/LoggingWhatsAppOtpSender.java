package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** ponytail: logs instead of sending — no WhatsApp Business API credentials until E17 is built. */
@Component
public class LoggingWhatsAppOtpSender implements WhatsAppOtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppOtpSender.class);

    @Override
    public void send(String mobilePhone, String code) {
        log.info("[MOCK WhatsApp OTP] would send {} to {}", code, mobilePhone);
    }
}
