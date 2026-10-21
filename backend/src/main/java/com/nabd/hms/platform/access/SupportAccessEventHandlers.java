package com.nabd.hms.platform.access;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** PLT-057: consumer of platform.support_access.decided.v1. Log-only until core.realtime (ACC-021) subscribes; the
 * clinic's audit_log row is written in the same transaction as the decision, so nothing waits on this. */
@Component
class SupportAccessEventHandlers implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SupportAccessEventHandlers.class);

    @Override
    public String eventType() {
        return SupportAccessService.DECIDED_EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("support access decided event {} for tenant {}", event.id(), event.tenantId());
    }
}
