package com.nabd.hms.staff;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Terminates iam.staff_role.changed.v1 (PLT-004) so the outbox does not retry/starve on an event type with no consumer yet. */
@Component
class StaffRoleChangedHandler implements OutboxEventHandler {

    static final String TYPE = "iam.staff_role.changed.v1";

    private static final Logger log = LoggerFactory.getLogger(StaffRoleChangedHandler.class);

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("{} delivered for tenant {}", event.eventType(), event.tenantId());
    }
}
