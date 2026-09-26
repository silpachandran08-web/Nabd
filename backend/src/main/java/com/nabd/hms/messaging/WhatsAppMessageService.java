package com.nabd.hms.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The one way to send a patient/clinic WhatsApp message. Call from inside your own @Transactional
 * business method: the message row and its outbox event commit with your write, or not at all.
 * Callers name a clinic template and fill its slots — they never supply message text (NB-191). */
@Service
public class WhatsAppMessageService {

    static final String EVENT_TYPE = "whatsapp.send";

    private final WhatsAppMessageRepository repo;
    private final WhatsAppTemplateRepository templates;
    private final EventPublisher publisher;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    WhatsAppMessageService(WhatsAppMessageRepository repo, WhatsAppTemplateRepository templates, EventPublisher publisher,
                           TenantContext tenantContext, ObjectMapper objectMapper) {
        this.repo = repo;
        this.templates = templates;
        this.publisher = publisher;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    /** @param patientId set for any patient-facing send — consent is checked at send time, not here,
     *                   so a withdrawal between queueing and sending still suppresses it (NB-196). */
    @Transactional
    public UUID queueTemplate(UUID tenantId, UUID patientId, String toPhone, String templateName,
                              String language, List<String> params) {
        tenantContext.set(tenantId);
        WhatsAppTemplateRepository.Template template = templates.findByName(tenantId, templateName, language)
                .filter(WhatsAppTemplateRepository.Template::sendable)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "whatsapp-template-not-sendable",
                        "WhatsApp template not available",
                        "No approved template called " + templateName + " in " + language + "."));
        TemplateRules.checkParams(params, template.paramCount());
        UUID id = repo.insert(tenantId, patientId, toPhone, template.id(), template.metaName(), language, toJson(params));
        publisher.publish(tenantId, EVENT_TYPE, Map.of("messageId", id.toString()));
        return id;
    }

    private String toJson(List<String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalArgumentException("WhatsApp template params are not serializable", e);
        }
    }
}
