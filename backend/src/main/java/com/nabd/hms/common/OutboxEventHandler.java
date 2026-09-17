package com.nabd.hms.common;

/**
 * Implemented by each consumer of a given outbox event type (WhatsApp, SMS, recall, webhooks —
 * see NB-007/NB-188). Delivery is at-least-once: a crash between {@link #handle} returning and
 * the event being marked done means handle() runs again for the same event, so implementations
 * must be idempotent. Runs inside {@link OutboxEventProcessor}'s own transaction with
 * TenantContext already set to the event's tenant — throw on failure and the dispatcher retries
 * with backoff up to a fixed attempt limit, then dead-letters the event.
 */
public interface OutboxEventHandler {

    /** Must be unique across every registered handler. */
    String eventType();

    void handle(OutboxEvent event) throws Exception;
}
