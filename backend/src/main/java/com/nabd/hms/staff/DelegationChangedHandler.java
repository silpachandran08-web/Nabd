package com.nabd.hms.staff;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Terminates iam.delegation.changed.v1 so the outbox does not dead-letter it; the core.realtime consumer is not built yet. */
@Component
class DelegationChangedHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DelegationChangedHandler.class);

    @Override
    public String eventType() {
        return RoleService.DELEGATION_EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("{} delivered for tenant {}", event.eventType(), event.tenantId());
    }
}
