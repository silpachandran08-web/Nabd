package com.nabd.hms.platform.tenant;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.platform.tenant.dto.LifecycleEventResponse;
import com.nabd.hms.platform.tenant.dto.TenantLifecycleResponse;
import com.nabd.hms.common.SessionRevocation;
import com.nabd.hms.shared.cache.CacheKeyPolicy;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The seven-state audited tenant lifecycle (NB-261, DPO-04/SSA-01). 'provisioning' is set by
 * ProvisioningStepRunner.createTenant and left behind by go_live, which calls transition() here
 * to promote to 'trialing' — closing the seam NB-258 deliberately left ("Promoting past 'trial' is
 * NB-261's tenant-lifecycle job, not this one's"). Every other transition is operator-driven via
 * the REST layer (billing/dunning triggers for active/overdue/suspended land with NB-267).
 * ADM-020: a move outside the table is 409 INVALID_TRANSITION, a reason is required at every door, every move publishes
 * organization.tenant.status_changed.v1, and the owners are told when the clinic is suspended or starts offboarding.
 */
@Service
public class TenantLifecycleService implements com.nabd.hms.common.TenantLifecyclePort {

    private static final String NOT_FOUND = "The requested tenant was not found.";

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "provisioning", Set.of("trialing"),
            "trialing", Set.of("active", "suspended", "offboarding"),
            "active", Set.of("overdue", "suspended", "offboarding"),
            "overdue", Set.of("active", "suspended", "offboarding"),
            "suspended", Set.of("active", "offboarding"),
            "offboarding", Set.of("offboarded"),
            "offboarded", Set.of() // terminal — no transition out
    );
    /** The seven states in ladder order (the console stepper shows the allowed next ones in this order). */
    private static final List<String> STATES = List.of("provisioning", "trialing", "active", "overdue", "suspended", "offboarding", "offboarded");
    private static final Set<String> OWNER_NOTIFIED = Set.of("suspended", "offboarding");

    /** The transition table: the states a tenant in {@code status} may move to (empty for offboarded and unknown). */
    static Set<String> allowedFrom(String status) {
        return ALLOWED_TRANSITIONS.getOrDefault(status, Set.of());
    }

    private final TenantLifecycleRepository repo;
    private final PlatformAudit audit;
    private final SessionRevocation.TenantRevoker sessions;
    private final com.nabd.hms.common.TenantContext tenantContext;
    private final TenantScope tenantScope;
    private final EventPublisher events;
    private final ActorDirectory actors;
    private final NotificationPort notifications;
    private final CacheKeyPolicy keys;

    private static final long STATUS_TTL_NANOS = 5_000_000_000L; // the DPA data gate's bound: every instance follows within 5 s, no cross-instance push

    private record CachedStatus(String status, long loadedAt) {
    }

    private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();

    TenantLifecycleService(TenantLifecycleRepository repo, PlatformAudit audit, SessionRevocation.TenantRevoker sessions,
                           com.nabd.hms.common.TenantContext tenantContext, TenantScope tenantScope, EventPublisher events,
                           ActorDirectory actors, NotificationPort notifications, CacheKeyPolicy keys) {
        this.keys = keys;
        this.tenantContext = tenantContext;
        this.tenantScope = tenantScope;
        this.events = events;
        this.actors = actors;
        this.notifications = notifications;
        this.repo = repo;
        this.audit = audit;
        this.sessions = sessions;
    }

    @Transactional
    public TenantLifecycleResponse transition(UUID tenantId, String toStatus, UUID changedBy, String reason,
                                              String ipAddress) {
        return move(tenantId, toStatus, changedBy, null, reason, ipAddress);
    }

    /** ADM-004/011: a move caused by the clinic owner (staffId) or by the trial job (staffId null); same rules and audit as an operator's. */
    @Override
    @Transactional
    public void transitionBy(String toStatus, UUID staffId, String reason) {
        move(tenantContext.current(), toStatus, null, staffId, reason, null);
    }

    @Override
    @Transactional
    public void markLive(UUID staffId) {
        repo.markLive(tenantContext.current(), staffId);
    }

    /** ADM-016: the one move out of 'offboarding' that is not 'offboarded': back to where the clinic was, on the owner's cancel. */
    private static final Set<String> RESTORABLE = Set.of("trialing", "active", "overdue", "suspended");

    @Override
    @Transactional
    public void restoreFromOffboarding(String previousStatus, UUID staffId, String reason) {
        move(tenantContext.current(), previousStatus, null, staffId, reason, null, true);
    }

    @Transactional
    TenantLifecycleResponse move(UUID tenantId, String toStatus, UUID changedBy, UUID changedByStaff, String reason,
                                         String ipAddress) {
        return move(tenantId, toStatus, changedBy, changedByStaff, reason, ipAddress, false);
    }

    @Transactional
    TenantLifecycleResponse move(UUID tenantId, String toStatus, UUID changedBy, UUID changedByStaff, String reason,
                                 String ipAddress, boolean cancelOffboarding) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required", "Give a reason for the lifecycle change.");
        }
        String fromStatus = repo.findTenantStatus(tenantId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        Set<String> allowed = cancelOffboarding && "offboarding".equals(fromStatus) ? RESTORABLE : allowedFrom(fromStatus);
        if (!allowed.contains(toStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Invalid lifecycle transition",
                    "A tenant in " + fromStatus + " cannot move to " + toStatus + ".");
        }
        repo.updateTenantStatus(tenantId, toStatus);
        evictStatusAtCommit(tenantId);
        long eventId = repo.insertEvent(tenantId, fromStatus, toStatus, changedBy, changedByStaff, reason);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fromStatus", fromStatus);
        after.put("toStatus", toStatus);
        after.put("reason", reason);
        if (SessionRevocation.INACTIVE_TENANT_STATUSES.contains(toStatus)) {
            after.put("sessionsRevoked", sessions.revokeAll(tenantId, "tenant_suspended")); // PLT-007: same transaction
        }
        if (changedByStaff != null) {
            after.put("byStaff", changedByStaff);
        }
        tenantScope.run(tenantId, () -> announce(tenantId, eventId, fromStatus, toStatus, reason)); // joins this transaction
        audit.record(tenantId, changedBy, ipAddress, "platform_tenant.lifecycle_changed", "tenant", tenantId, after);
        log.info("tenant {} transitioned {} -> {} by operator {}", tenantId, fromStatus, toStatus, changedBy);
        return getLifecycle(tenantId);
    }

    /** The event for comms-gateway / the control plane, and a message to each owner when the clinic is suspended or starts offboarding. */
    private void announce(UUID tenantId, long eventId, String fromStatus, String toStatus, String reason) {
        // the event carries at most 500 characters of the reason (its schema's bound); the history and the audit log keep all of it
        events.publish(tenantId, DomainEventType.TENANT_STATUS_CHANGED, tenantId,
                Map.of("from", fromStatus, "to", toStatus, "reason", reason.length() > 500 ? reason.substring(0, 500) : reason));
        // no approved template = no message (never a failed transition); the owner still sees the in-app banner
        if (!OWNER_NOTIFIED.contains(toStatus) || !notifications.sendable(NotificationPurpose.TENANT_STATUS_CHANGED, "en")) {
            return;
        }
        for (ActorDirectory.OwnerContact o : actors.activeOwners(tenantId)) {
            if (o.mobilePhone() != null && !o.mobilePhone().isBlank()) {
                notifications.request(new NotificationRequest(NotificationPurpose.TENANT_STATUS_CHANGED, "tenant", tenantId, null,
                        o.mobilePhone(), "en", List.of(toStatus), null, null, null, "tenant-status:" + eventId + ":" + o.id()));
            }
        }
    }

    /** ADM-020: the current status alone (the staff banner reads it); empty for an unknown tenant. */
    @Transactional(readOnly = true)
    public java.util.Optional<String> statusOf(UUID tenantId) {
        return repo.findTenantStatus(tenantId);
    }

    /**
     * ADM-020 / OPS-020: the status for the suspended write block, which asks on every staff write (check-in included), so it
     * comes from this instance's cache: a warm write sends no statement and opens no transaction. A move through this service
     * drops the entry at commit; another instance, or a status set by another door (a billing job's UPDATE), is followed
     * within 5 s. An unknown tenant is never cached.
     */
    public java.util.Optional<String> cachedStatusOf(UUID tenantId) {
        String key = statusKey(tenantId);
        CachedStatus c = statusCache.get(key);
        long now = System.nanoTime();
        if (c != null && now - c.loadedAt() < STATUS_TTL_NANOS) {
            return java.util.Optional.of(c.status());
        }
        java.util.Optional<String> status = repo.findTenantStatus(tenantId); // tenants carries no RLS: one statement, no tenant bind
        // shortcut: a load racing a move can put the old status back until the TTL; a version check if 5 s ever matters
        status.ifPresent(s -> statusCache.put(key, new CachedStatus(s, now)));
        return status;
    }

    private void evictStatusAtCommit(UUID tenantId) {
        String key = statusKey(tenantId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            statusCache.remove(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                statusCache.remove(key); // not before: a write in between would cache the status this move replaces
            }
        });
    }

    private String statusKey(UUID tenantId) {
        return keys.key("tenant-status", tenantId, List.of("tenants.status"), false, "row");
    }

    public TenantLifecycleResponse getLifecycle(UUID tenantId) {
        String status = repo.findTenantStatus(tenantId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        var events = repo.listEvents(tenantId).stream()
                .map(e -> new LifecycleEventResponse(e.fromStatus(), e.toStatus(), e.changedBy(), e.reason(), e.changedAt(),
                        e.actorType(), e.operatorName()))
                .toList();
        String[] label = repo.findTenantLabel(tenantId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        return new TenantLifecycleResponse(tenantId, status, events, STATES.stream().filter(allowedFrom(status)::contains).toList(),
                label[0], label[1]);
    }
}
