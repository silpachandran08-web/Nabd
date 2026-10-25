package com.nabd.hms.platform.entitlements;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-059 / ADM-034: publishes platform.entitlements.changed.v1 for one clinic and drops this replica's cache entry.
 * Own transaction per clinic: an operator request may touch several clinics and one transaction binds one tenant.
 * Other replicas converge on their 60 s snapshot TTL (and the event for edge services).
 */
@Component
public class EntitlementEvents {

    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final PlanEntitlements cache;

    EntitlementEvents(PlatformTransactionManager txm, EventPublisher events, PlanEntitlements cache) {
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.events = events;
        this.cache = cache;
    }

    public void changed(UUID tenantId, List<String> changedCodes) {
        cache.evict(tenantId);
        tx.executeWithoutResult(s -> {
            events.publish(tenantId, DomainEventType.PLATFORM_ENTITLEMENTS_CHANGED, tenantId, Map.of("changedCodes", changedCodes));
        });
    }
}
