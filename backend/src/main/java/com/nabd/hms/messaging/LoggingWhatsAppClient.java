package com.nabd.hms.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/** Local/test fallback when app.whatsapp.access-token is blank — logs instead of sending. */
class LoggingWhatsAppClient implements WhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppClient.class);

    @Override
    public String sendTemplate(String toPhone, String templateName, String language, List<String> bodyParams) {
        log.info("[MOCK WhatsApp] would send template {} ({}) to {} with {}", templateName, language, toPhone, bodyParams);
        return "mock." + UUID.randomUUID();
    }

    @Override
    public String sendOtp(String toPhone, String templateName, String language, String code) {
        log.info("[MOCK WhatsApp OTP] would send {} to {}", code, toPhone);
        return "mock." + UUID.randomUUID();
    }

    /** Auto-approves, so local runs can send through a template without a Meta review. */
    @Override
    public Submission createTemplate(String metaName, String language, String category, String headerText,
                                     String body, List<String> bodyExamples) {
        log.info("[MOCK WhatsApp] would submit template {} ({}, {})", metaName, language, category);
        return new Submission("mock." + UUID.randomUUID(), "approved");
    }

    @Override
    public void editTemplate(String metaTemplateId, String headerText, String body, List<String> bodyExamples) {
        log.info("[MOCK WhatsApp] would resubmit template {}", metaTemplateId);
    }
}
