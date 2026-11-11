package com.nabd.hms.messaging;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.OutboxRetryLaterException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.config.OutboxProperties;
import org.springframework.stereotype.Component;
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
    private final TenantScope tenantScope;
    private final int inFlightSeconds;
    private final OptOutRegistry optOut;
    private final WhatsAppMessageService messages;
    private final java.time.Clock clock;

    WhatsAppSendHandler(WhatsAppMessageRepository repo, WhatsAppClient client, ObjectMapper objectMapper,
                        TenantScope tenantScope, OutboxProperties outbox, OptOutRegistry optOut, WhatsAppMessageService messages,
                        java.time.Clock clock) {
        this.messages = messages;
        this.clock = clock;
        this.optOut = optOut;
        this.repo = repo;
        this.client = client;
        this.objectMapper = objectMapper;
        this.tenantScope = tenantScope;
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
        UUID id = UUID.fromString(objectMapper.readTree(event.payloadJson()).path("messageId").asString());
        int[] attemptHolder = new int[1];
        WhatsAppMessageRepository.Message msg = inTx(event, () -> claimForSend(id, event.tenantId(), attemptHolder));
        if (msg == null) {
            return;
        }
        int attempt = attemptHolder[0];
        List<String> params = objectMapper.readValue(msg.paramsJson(), new TypeReference<>() { });
        String wamid;
        try {
            wamid = client.sendTemplate(msg.toPhone(), msg.sendName(), msg.language(), params); // COM-013: the active version
        } catch (RestClientResponseException e) {
            // 4xx (bad number, unapproved template, bad params) won't fix itself on retry — record it
            // and stop. 429/5xx rethrow so the outbox backs off and retries.
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 429) {
                inTx(event, () -> {
                    repo.markFailed(id, "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), attempt);
                    return null;
                });
                return;
            }
            requeue(event, id, attempt);
            throw e;
        } catch (RuntimeException e) {
            requeue(event, id, attempt); // timeout, connection refused, circuit open: the outbox retries with backoff
            throw e;
        }
        inTx(event, () -> repo.markSent(id, wamid, attempt)); // fenced: 0 rows when a newer attempt owns the message
    }

    /** Runs in the short first transaction. Returns the message to send, or null when there is nothing to send. */
    private WhatsAppMessageRepository.Message claimForSend(UUID id, UUID tenantId, int[] attemptOut) {
        WhatsAppMessageRepository.Message msg = repo.findForUpdate(id).orElse(null); // OPS-014: row lock, see findForUpdate
        if (msg == null || !("queued".equals(msg.status()) || "sending".equals(msg.status()))) {
            return null;
        }
        if ("sending".equals(msg.status()) && repo.sendInFlight(id, inFlightSeconds)) {
            throw new OutboxRetryLaterException("send of message " + id + " still in flight elsewhere", inFlightSeconds);
        }
        java.time.Instant now = clock.instant();
        if (msg.notAfter() != null && !now.isBefore(msg.notAfter())) { // COM-013: never sent late
            messages.expireAtClaim(id);
            return null;
        }
        boolean pauseOver = "paused".equals(msg.templateStatus()) && msg.pausedUntil() != null && !msg.pausedUntil().isAfter(now);
        if ("paused".equals(msg.templateStatus()) && !pauseOver && msg.notAfter() != null) {
            // COM-013: Meta's pause is 3 h (6 h the second time). Hold a send with a window until it lifts; if the window
            // ends first it is expired now rather than sent late. One retry, at the pause end (outbox attempts are capped).
            java.time.Instant until = msg.pausedUntil() == null ? now.plus(TemplateLifecycle.FIRST_PAUSE) : msg.pausedUntil();
            if (!msg.notAfter().isAfter(until)) {
                messages.expireAtClaim(id);
                return null;
            }
            throw new OutboxRetryLaterException("template of message " + id + " is paused", java.time.Duration.between(now, until).toSeconds() + 1);
        }
        if (msg.templateStatus() != null && !"approved".equals(msg.templateStatus()) && !"flagged".equals(msg.templateStatus()) && !pauseOver) {
            // A send with no window (no not_after) still fails at once on a paused template: nothing says how long it may wait.
            repo.markFailed(id, "template is " + msg.templateStatus());
            return null;
        }
        if (msg.patientId() != null && !repo.hasMessagingConsent(msg.patientId())) {
            repo.markBlocked(id, "no active messaging consent");
            return null;
        }
        if (msg.patientId() != null && "MARKETING".equals(msg.templateCategory()) && !repo.hasConsent(msg.patientId(), "marketing")) {
            repo.markBlocked(id, "marketing template needs marketing consent"); // COM-013: also after Meta re-categorised it
            return null;
        }
        if (!isOptOutConfirmation(msg) && optedOut(tenantId, msg)) {
            repo.markBlocked(id, "recipient opted out"); // COM-002: STOP by phone, also for sends with no patient record
            return null;
        }
        attemptOut[0] = repo.markSending(id);
        return msg;
    }

    /** The confirmations of an opt-out / opt-in are the one message allowed to an opted-out number (utility, Meta policy). */
    private static boolean isOptOutConfirmation(WhatsAppMessageRepository.Message msg) {
        return OptOutService.CONFIRM_STOP.equals(msg.templateKey()) || OptOutService.CONFIRM_START.equals(msg.templateKey());
    }

    private boolean optedOut(UUID tenantId, WhatsAppMessageRepository.Message msg) {
        return optOut.isSuppressed(tenantId, msg.toPhone(), OptOutRegistry.MESSAGING)
                || ("MARKETING".equals(msg.templateCategory()) && optOut.isSuppressed(tenantId, msg.toPhone(), OptOutRegistry.MARKETING));
    }

    private void requeue(OutboxEvent event, UUID id, int attempt) {
        inTx(event, () -> {
            repo.requeue(id, attempt);
            return null;
        });
    }

    private <T> T inTx(OutboxEvent event, Supplier<T> work) {
        return tenantScope.run(event.tenantId(), work);
    }
}
