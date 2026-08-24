package com.nabd.hms.common;

/**
 * WhatsApp Business API integration lives in the Messaging Platform epic (E17), not built yet.
 * This interface is the seam: swap {@link LoggingWhatsAppOtpSender} for a real implementation
 * once that epic lands, without touching AuthService.
 */
public interface WhatsAppOtpSender {
    void send(String mobilePhone, String code);
}
