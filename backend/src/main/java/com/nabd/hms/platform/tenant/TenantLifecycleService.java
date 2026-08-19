package com.nabd.hms.platform.tenant;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.tenant.dto.LifecycleEventResponse;
import com.nabd.hms.platform.tenant.dto.TenantLifecycleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The seven-state audited tenant lifecycle (NB-261, DPO-04/SSA-01). 'provisioning' is set by
 * ProvisioningStepRunner.createTenant and left behind by go_live, which calls transition() here
 * to promote to 'trialing' — closing the seam NB-258 deliberately left ("Promoting past 'trial' is
 * NB-261's tenant-lifecycle job, not this one's"). Every other transition is operator-driven via
 * the REST layer (billing/dunning triggers for active/overdue/suspended land with NB-267).
 */
@Service
public class TenantLifecycleService {

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

    private final TenantLifecycleRepository repo;

    TenantLifecycleService(TenantLifecycleRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public TenantLifecycleResponse transition(UUID tenantId, String toStatus, UUID changedBy, String reason) {
        String fromStatus = repo.findTenantStatus(tenantId).orElseThrow(this::notFound);
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(fromStatus, Set.of());
        if (!allowed.contains(toStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-lifecycle-transition", "Invalid lifecycle transition",
                    "A tenant in " + fromStatus + " cannot move to " + toStatus + ".");
        }
        repo.updateTenantStatus(tenantId, toStatus);
        repo.insertEvent(tenantId, fromStatus, toStatus, changedBy, reason);
        log.info("tenant {} transitioned {} -> {} by operator {} ({})", tenantId, fromStatus, toStatus, changedBy, reason);
        return getLifecycle(tenantId);
    }

    public TenantLifecycleResponse getLifecycle(UUID tenantId) {
        String status = repo.findTenantStatus(tenantId).orElseThrow(this::notFound);
        var events = repo.listEvents(tenantId).stream()
                .map(e -> new LifecycleEventResponse(e.fromStatus(), e.toStatus(), e.changedBy(), e.reason(), e.changedAt()))
                .toList();
        return new TenantLifecycleResponse(tenantId, status, events);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested tenant was not found.");
    }
}
