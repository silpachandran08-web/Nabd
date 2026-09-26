package com.nabd.hms.common;

/**
 * Staff login OTP delivery over WhatsApp (NB-040).
 * Implemented by messaging.TemplateWhatsAppOtpSender (Meta Cloud API, or a logging fallback when
 * app.whatsapp.access-token is blank).
 */
public interface WhatsAppOtpSender {
    void send(String mobilePhone, String code);
}
