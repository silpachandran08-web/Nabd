package com.nabd.hms.platform.activation;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;

/** ADM-005: consumer of the wizard's organization.setup_step.changed.v1 (published by organization.setup.SetupStepEvents; the
 * name is repeated here because platform does not depend on organization internals). Runs in the event's tenant transaction. */
@Component
class ActivationEventHandler implements OutboxEventHandler {

    static final String EVENT = "organization.setup_step.changed.v1";

    private final ActivationService service;

    ActivationEventHandler(ActivationService service) {
        this.service = service;
    }

    @Override
    public String eventType() {
        return EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        service.apply(event);
    }
}
