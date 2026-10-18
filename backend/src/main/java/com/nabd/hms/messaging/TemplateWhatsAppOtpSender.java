package com.nabd.hms.messaging;

import com.nabd.hms.common.WhatsAppOtpSender;
import org.springframework.stereotype.Component;

/** Sent synchronously, not via the outbox — a login code that arrives after a 30s retry backoff
 * is useless, so a failure should surface to the caller now. */
@Component
class TemplateWhatsAppOtpSender implements WhatsAppOtpSender {

    private final WhatsAppClient client;
    private final WhatsAppProperties props;

    TemplateWhatsAppOtpSender(WhatsAppClient client, WhatsAppProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public void send(String mobilePhone, String code) {
        client.sendOtp(mobilePhone, props.otpTemplate(), props.otpLanguage(), code);
    }
}
