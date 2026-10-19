package com.nabd.hms.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.OutboxRetryLaterException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.config.OutboxProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Delivers one queued whatsapp_messages row. Idempotent on the row's status: anything past
 * 'queued' was already handled, so a redelivered event is a no-op.
 *
 * <p>OPS-012: commit intent, call Meta with no transaction open, record the result in a short transaction.
 * (1) Short tx: lock the row (OPS-014), check template/consent, mark it 'sending'. (2) The HTTP call. (3) Short tx:
 * 'sent' with the wamid, 'failed' on a 4xx, or back to 'queued' when a retry is safe. A second worker that finds a
 * fresh 'sending' row (younger than one lease) backs off instead of sending again; an older one means the first
 * sender is gone (its call is bounded by the per-event timeout, which is below the lease), so it re-sends.
 *
 * <p>shortcut: a provider timeout after Meta accepted the send, or a crash between (2) and (3), re-sends once on
 * retry (at-least-once, per OutboxEventHandler's contract). Meta has no idempotency key for template sends; accept
 * the rare duplicate over a lost reminder.
 */
@Component
class WhatsAppSendHandler implements OutboxEventHandler {

    private final WhatsAppMessageRepository repo;
    private final WhatsAppClient client;
    private final ObjectMapper objectMapper;
    private final TenantContext tenantContext;
    private final TransactionTemplate tx;
    private final int inFlightSeconds;

    WhatsAppSendHandler(WhatsAppMessageRepository repo, WhatsAppClient client, ObjectMapper objectMapper,
                        TenantContext tenantContext, PlatformTransactionManager txManager, OutboxProperties outbox) {
        this.repo = repo;
        this.client = client;
        this.objectMapper = objectMapper;
        this.tenantContext = tenantContext;
        this.tx = new TransactionTemplate(txManager);
        this.inFlightSeconds = outbox.leaseSeconds();
    }

    @Override
    public String eventType() {
        return WhatsAppMessageService.EVENT_TYPE;
    }

    @Override
    public boolean callsExternalProvider() {
        return true;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        UUID id = UUID.fromString(objectMapper.readTree(event.payloadJson()).path("messageId").asText());
        WhatsAppMessageRepository.Message msg = inTx(event, () -> claimForSend(id));
        if (msg == null) {
            return;
        }
        List<String> params = objectMapper.readValue(msg.paramsJson(), new TypeReference<>() { });
        String wamid;
        try {
            wamid = client.sendTemplate(msg.toPhone(), msg.templateName(), msg.language(), params);
        } catch (RestClientResponseException e) {
            // 4xx (bad number, unapproved template, bad params) won't fix itself on retry — record it
            // and stop. 429/5xx rethrow so the outbox backs off and retries.
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 429) {
                inTx(event, () -> {
                    repo.markFailed(id, "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
                    return null;
                });
                return;
            }
            requeue(event, id);
            throw e;
        } catch (RuntimeException e) {
            requeue(event, id); // timeout, connection refused, circuit open: the outbox retries with backoff
            throw e;
        }
        inTx(event, () -> repo.markSent(id, wamid));
    }

    /** Runs in the short first transaction. Returns the message to send, or null when there is nothing to send. */
    private WhatsAppMessageRepository.Message claimForSend(UUID id) {
        WhatsAppMessageRepository.Message msg = repo.findForUpdate(id).orElse(null); // OPS-014: row lock, see findForUpdate
        if (msg == null || !("queued".equals(msg.status()) || "sending".equals(msg.status()))) {
            return null;
        }
        if ("sending".equals(msg.status()) && repo.sendInFlight(id, inFlightSeconds)) {
            throw new OutboxRetryLaterException("send of message " + id + " still in flight elsewhere", inFlightSeconds);
        }
        if (msg.templateStatus() != null && !"approved".equals(msg.templateStatus()) && !"flagged".equals(msg.templateStatus())) {
            // ponytail: Meta un-pauses templates after a few hours; failing now beats holding the event that long.
            repo.markFailed(id, "template is " + msg.templateStatus());
            return null;
        }
        if (msg.patientId() != null && !repo.hasMessagingConsent(msg.patientId())) {
            repo.markBlocked(id, "no active messaging consent");
            return null;
        }
        repo.markSending(id);
        return msg;
    }

    private void requeue(OutboxEvent event, UUID id) {
        inTx(event, () -> {
            repo.requeue(id);
            return null;
        });
    }

    private <T> T inTx(OutboxEvent event, Supplier<T> work) {
        return tx.execute(status -> {
            tenantContext.set(event.tenantId());
            return work.get();
        });
    }
}
