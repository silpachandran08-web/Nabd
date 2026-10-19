package com.nabd.hms.clinical;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;

/** CLN-005: acknowledges clinical.prescription.signed.v1 so the dispatcher does not retry it; pharmacy worklist,
 * comms-gateway and clinical-intelligence consumers are later stories. */
@Component
class PrescriptionSignedHandler implements OutboxEventHandler {

    @Override
    public String eventType() {
        return PrescriptionService.SIGNED_EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        // intentionally empty: no consumer yet
    }
}
