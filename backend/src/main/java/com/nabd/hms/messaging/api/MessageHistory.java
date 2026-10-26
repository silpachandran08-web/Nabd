package com.nabd.hms.messaging.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** COM-006: what the patient file shows about messages. Runs in the caller's tenant transaction (RLS). */
public interface MessageHistory {

    /** No rendered body, no phone number, no template variables: template name, purpose, status, time, reason. */
    record Item(UUID id, String channel, String purpose, String templateName, String status, Instant statusAt,
                String failureReason, UUID fallbackOf) {
    }

    record Page(List<Item> items, String nextCursor) {
    }

    /** Newest first; {@code cursor} is the opaque value of a previous page's nextCursor (400 invalid-cursor when malformed). */
    Page page(UUID patientId, String cursor, int limit);
}
