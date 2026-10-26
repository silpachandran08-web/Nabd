package com.nabd.hms.auth;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.SessionRevocation;
import com.nabd.hms.common.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PLT-007: called by the platform tenant lifecycle (through the kernel interface) inside its transaction. */
@Component
class TenantSessionRevoker implements SessionRevocation.TenantRevoker {

    private static final Logger log = LoggerFactory.getLogger(TenantSessionRevoker.class);

    private final AuthRepository repo;
    private final TenantScope tenantScope;
    private final EventPublisher events;

    TenantSessionRevoker(AuthRepository repo, TenantScope tenantScope, EventPublisher events) {
        this.repo = repo;
        this.tenantScope = tenantScope;
        this.events = events;
    }

    @Override
    public int revokeAll(UUID tenantId, String reason) {
        return tenantScope.run(tenantId, () -> { // joins the lifecycle transaction (PLT-019: binds or checks the tenant)
            List<UUID> staffIds = repo.revokeAllForTenant(tenantId, reason);
            // OPS-015 schema api/events/iam.session.revoked.v1: one event per staff member, as StaffService publishes
            for (UUID staffId : new LinkedHashSet<>(staffIds)) {
                events.publish(tenantId, DomainEventType.SESSION_REVOKED, staffId,
                        Map.of("staffId", staffId, "scope", "all_sessions", "reasonCode", reason, "status", "revoked"));
            }
            log.warn("tenant {}: {} sessions revoked ({})", tenantId, staffIds.size(), reason); // log-phi-ok: revocation reason is a fixed lifecycle code
            return staffIds.size();
        });
    }
}
