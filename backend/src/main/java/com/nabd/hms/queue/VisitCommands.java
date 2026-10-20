package com.nabd.hms.queue;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** The visit module's command API for other modules. Billing's checkout is the only path to 'completed'. */
@Component
public class VisitCommands {

    private final QueueService queue;

    VisitCommands(QueueService queue) {
        this.queue = queue;
    }

    /** CAS from {@code expectedStatus} (checkout_pending or the terminal billing stop) to completed, in the caller's payment transaction. */
    @Transactional
    public void completeAfterPayment(UUID tenantId, UUID staffId, UUID queueEntryId, String expectedStatus) {
        queue.move(tenantId, staffId, queueEntryId, expectedStatus, "completed");
    }
}
