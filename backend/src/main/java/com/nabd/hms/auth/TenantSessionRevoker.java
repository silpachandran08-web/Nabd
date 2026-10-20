package com.nabd.hms.auth;

import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.SessionRevocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** PLT-007: called by the platform tenant lifecycle (through the kernel interface) inside its transaction. */
@Component
class TenantSessionRevoker implements SessionRevocation.TenantRevoker {

    private static final Logger log = LoggerFactory.getLogger(TenantSessionRevoker.class);

    private final AuthRepository repo;
    private final TenantContext tenantContext;
    private final EventPublisher events;

    TenantSessionRevoker(AuthRepository repo, TenantContext tenantContext, EventPublisher events) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.events = events;
    }

    @Override
    public int revokeAll(UUID tenantId, String reason) {
        tenantContext.set(tenantId);
        List<UUID> ids = repo.revokeAllForTenant(tenantId, reason);
        events.publish(tenantId, SessionRevocation.EVENT, SessionRevocation.payload(tenantId, null, ids, reason));
        log.warn("tenant {}: {} sessions revoked ({})", tenantId, ids.size(), reason);
        return ids.size();
    }
}
