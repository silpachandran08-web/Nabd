package com.nabd.hms.packages;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;

/** FIN-004: acknowledges billing.package_refund.approved.v1 so the dispatcher does not dead-letter it; reporting and comms consumers are later stories. */
@Component
class PackageRefundApprovedHandler implements OutboxEventHandler {

    static final String EVENT_TYPE = "billing.package_refund.approved.v1";

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        // intentionally empty: no consumer yet
    }
}
