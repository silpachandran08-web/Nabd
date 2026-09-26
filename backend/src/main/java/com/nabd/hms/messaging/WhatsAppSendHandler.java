package com.nabd.hms.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;

/**
 * Delivers one queued whatsapp_messages row. Idempotent on the row's status: anything past
 * 'queued' was already handled, so a redelivered event is a no-op.
 *
 * <p>ponytail: a crash after Meta accepts the send but before this transaction commits re-sends
 * once on retry (at-least-once, per OutboxEventHandler's contract). Meta has no idempotency key
 * for template sends; accept the rare duplicate over a lost reminder.
 */
@Component
class WhatsAppSendHandler implements OutboxEventHandler {

    private final WhatsAppMessageRepository repo;
    private final WhatsAppClient client;
    private final ObjectMapper objectMapper;

    WhatsAppSendHandler(WhatsAppMessageRepository repo, WhatsAppClient client, ObjectMapper objectMapper) {
        this.repo = repo;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return WhatsAppMessageService.EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        UUID id = UUID.fromString(objectMapper.readTree(event.payloadJson()).path("messageId").asText());
        WhatsAppMessageRepository.Message msg = repo.find(id).orElse(null);
        if (msg == null || !"queued".equals(msg.status())) {
            return;
        }
        if (msg.templateStatus() != null && !"approved".equals(msg.templateStatus()) && !"flagged".equals(msg.templateStatus())) {
            // ponytail: Meta un-pauses templates after a few hours; failing now beats holding the event that long.
            repo.markFailed(id, "template is " + msg.templateStatus());
            return;
        }
        if (msg.patientId() != null && !repo.hasMessagingConsent(msg.patientId())) {
            repo.markBlocked(id, "no active messaging consent");
            return;
        }
        List<String> params = objectMapper.readValue(msg.paramsJson(), new TypeReference<>() { });
        try {
            repo.markSent(id, client.sendTemplate(msg.toPhone(), msg.templateName(), msg.language(), params));
        } catch (RestClientResponseException e) {
            // 4xx (bad number, unapproved template, bad params) won't fix itself on retry — record it
            // and stop. 429/5xx rethrow so the outbox backs off and retries.
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 429) {
                repo.markFailed(id, "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
                return;
            }
            throw e;
        }
    }
}
