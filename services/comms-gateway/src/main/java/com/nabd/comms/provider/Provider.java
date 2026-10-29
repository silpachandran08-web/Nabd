package com.nabd.comms.provider;

import java.util.UUID;

/**
 * The port to the messaging provider (Meta Cloud API / SMS BSP). The real HTTP client is COM-007's and needs Meta (external action);
 * tests use the fault-injecting stub in src/test (COM-010).
 */
public interface Provider {

    record OutboundRequest(UUID messageId, String phoneNumberId, String to, String templateName) {}

    sealed interface SendResult permits Accepted, RateLimited, Transient, Rejected {}

    record Accepted(String providerMessageId) implements SendResult {}

    /** HTTP 429 / error 130429: the sender's bucket must shrink; the message is retried, never dropped. */
    record RateLimited(String code) implements SendResult {}

    /** 5xx, timeout, provider down: retry with backoff. */
    record Transient(String code) implements SendResult {}

    /** 4xx that retrying cannot fix: dead letter. */
    record Rejected(String code) implements SendResult {}

    /** quality_rating: GREEN, YELLOW, RED or UNKNOWN; tier e.g. TIER_1K. */
    record PhoneStatus(String rating, String tier) {}

    SendResult send(OutboundRequest request);

    PhoneStatus phoneStatus(String phoneNumberId);
}
