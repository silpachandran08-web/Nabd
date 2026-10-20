package com.nabd.hms.messaging;

import com.nabd.hms.common.WhatsAppOtpSender;
import org.springframework.stereotype.Component;

/** Called by auth.OtpRequestedHandler from the outbox with no transaction open (OPS-012): a Meta slowdown must
 * not hold request threads and DB connections. A failed send is retried with the outbox backoff while the code
 * is still valid. */
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
