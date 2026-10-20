package com.nabd.hms.auth;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.SessionRevocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Terminates iam.session.revoked.v1 (PLT-007). Open tabs already sign out via 401 on their next call (staff-web). */
@Component
class SessionRevokedHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionRevokedHandler.class);

    @Override
    public String eventType() {
        return SessionRevocation.EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("{} delivered for tenant {}", event.eventType(), event.tenantId());
    }
}
