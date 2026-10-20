package com.nabd.hms.pharmacy;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;

/** PHL-001: acknowledges pharmacy.rx_gate.blocked.v1 so the dispatcher does not retry it; core.reporting consumes it later. */
@Component
class RxGateBlockedHandler implements OutboxEventHandler {

    @Override
    public String eventType() {
        return RxGateBlockRecorder.EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        // intentionally empty: no consumer yet
    }
}
