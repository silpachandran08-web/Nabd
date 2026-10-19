package com.nabd.hms.billing;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** FIN-001: acknowledges billing.payment.recorded.v1 so the outbox row completes. The realtime, reporting and
 * comms consumers register their own handlers when those stories land; this one only logs ids (no PHI). */
@Component
class PaymentRecordedHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecordedHandler.class);

    static final String TYPE = "billing.payment.recorded.v1";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        log.debug("payment event {} delivered (tenant {})", event.id(), event.tenantId());
    }
}
