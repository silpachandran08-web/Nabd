package com.nabd.hms.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Meta WhatsApp Cloud API settings. Blank accessToken = no real sends (LoggingWhatsAppClient),
 * same fallback shape as spring.mail.host / MailConfig. */
@ConfigurationProperties(prefix = "app.whatsapp")
public record WhatsAppProperties(
        String apiBaseUrl,
        String phoneNumberId,
        String businessAccountId, // the WABA templates are registered on
        String accessToken,
        String appSecret,         // signs webhook payloads (X-Hub-Signature-256)
        String verifyToken,       // echoed back during Meta's webhook subscription handshake
        String otpTemplate,       // an approved AUTHENTICATION-category template with a copy-code button
        String otpLanguage
) {
}
