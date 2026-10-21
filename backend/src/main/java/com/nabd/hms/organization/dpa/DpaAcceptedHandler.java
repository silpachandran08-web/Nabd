package com.nabd.hms.organization.dpa;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;

/** Consumer of organization.dpa.accepted.v1: drops this instance's cached gate so the tenant is live at once (other instances: TTL 5 s). */
@Component
class DpaAcceptedHandler implements OutboxEventHandler {

    private final DataGate gate;

    DpaAcceptedHandler(DataGate gate) {
        this.gate = gate;
    }

    @Override
    public String eventType() {
        return DpaService.EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        gate.evict(event.tenantId());
    }
}
