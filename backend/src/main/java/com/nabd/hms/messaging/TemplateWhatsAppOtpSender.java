package com.nabd.hms.messaging;

import com.nabd.hms.common.WhatsAppOtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Sent synchronously, not via the outbox — a login code that arrives after a 30s retry backoff
 * is useless, so a failure should surface to the caller now. */
@Component
class TemplateWhatsAppOtpSender implements WhatsAppOtpSender {

    private static final Logger log = LoggerFactory.getLogger(TemplateWhatsAppOtpSender.class);

    private final WhatsAppClient client;
    private final WhatsAppProperties props;

    TemplateWhatsAppOtpSender(WhatsAppClient client, WhatsAppProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public void send(String mobilePhone, String code) {
        if (props.logOtp()) {
            // ponytail: OTPs in the log are readable by anyone with log access — set WHATSAPP_LOG_OTP=false in production.
            log.warn("[OTP] {} for {}", code, mobilePhone);
        }
        client.sendOtp(mobilePhone, props.otpTemplate(), props.otpLanguage(), code);
    }
}
