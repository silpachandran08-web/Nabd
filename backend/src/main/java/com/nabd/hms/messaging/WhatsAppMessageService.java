package com.nabd.hms.messaging;

import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
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
    private final Clock clock;

    WhatsAppMessageService(WhatsAppMessageRepository repo, WhatsAppTemplateRepository templates, EventPublisher publisher,
                           TenantContext tenantContext, ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.templates = templates;
        this.publisher = publisher;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** @param patientId set for any patient-facing send — consent is checked at send time, not here,
     *                   so a withdrawal between queueing and sending still suppresses it (NB-196). */
    @Transactional
    public UUID queueTemplate(UUID patientId, String toPhone, String templateName,
                              String language, List<String> params) {
        UUID tenantId = tenantContext.current(); // PLT-019: bound at transaction start (request) or by TenantScope (worker)
        WhatsAppTemplateRepository.Template template = templates.findByName(tenantId, templateName, language)
                .filter(WhatsAppTemplateRepository.Template::sendable)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "whatsapp-template-not-sendable",
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

    /** COM-003: a send that is no longer wanted. True when it was stopped; false when it had already started
     * sending (or is past it): a message that may be at the provider is never silently relabelled. */
    @Transactional
    public boolean cancel(UUID messageId) {
        return stop(messageId, "cancelled");
    }

    /** COM-003: a send that missed its window (COM-023 decides the window; this is the state change). */
    @Transactional
    public boolean expire(UUID messageId) {
        return stop(messageId, "expired");
    }

    @Transactional
    boolean stop(UUID messageId, String status) {
        if (!repo.markTerminal(messageId, status)) {
            return false;
        }
        Map<String, Object> data = new LinkedHashMap<>(); // same event as the delivery callbacks (COM-001): one schema, status delivered|read|failed|cancelled|expired
        data.put("messageId", messageId.toString());
        data.put("channel", "whatsapp");
        data.put("status", status);
        data.put("at", clock.instant().toString());
        publisher.publish(tenantContext.current(), DomainEventType.MESSAGE_STATUS_CHANGED, messageId, data);
        return true;
    }
}
