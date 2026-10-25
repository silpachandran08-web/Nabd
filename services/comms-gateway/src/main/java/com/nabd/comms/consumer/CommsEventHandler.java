package com.nabd.comms.consumer;

import java.util.Set;
import java.util.UUID;

/** COM-007: extension point for the Comms modules (COM-008/011 add the senders). handle() runs inside the ledger transaction; throw to retry. */
public interface CommsEventHandler {

    /** CloudEvents types handled, e.g. comms.notification.requested.v1. */
    Set<String> types();

    void handle(Event event);

    /** data is PHI-free by contract (docs/architecture/events.md section 3). */
    record Event(String id, String type, UUID tenantId, String dataJson) {
    }
}
