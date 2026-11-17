package com.nabd.hms.queue.api;

import java.util.UUID;

/**
 * PLT-035: the visit module's command API for billing. Billing never writes queue_entries; it moves a visit only through these,
 * inside its own checkout transaction, so the move and the bill commit together. Both are compare-and-set on {@code expectedStatus}
 * (409 stale-status when the visit moved meanwhile) and keep the department flow's legality rules.
 */
public interface VisitCommands {

    /** MF-28: the only way to 'completed'. Billing calls it after it settled the visit's invoice at the terminal billing stop. */
    void completeVisit(UUID tenantId, UUID staffId, UUID visitId, String expectedStatus);

    /** NB-355: an interim billing stop (billing_pending) moves the visit to the next step of its flow; never to 'completed'. */
    void advanceAfterBilling(UUID tenantId, UUID staffId, UUID visitId, String expectedStatus, String nextStatus);
}
