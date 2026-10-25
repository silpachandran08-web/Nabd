package com.nabd.hms.pharmacy;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * PHL-001: the audit row and event for a refused Rx line. Own transaction (REQUIRES_NEW) because the checkout that
 * was refused rolls back. Item code, catalogue id, reason and actor only: no patient name or drug free text.
 */
@Component
class RxGateBlockRecorder {

    static final String EVENT = "pharmacy.rx_gate.blocked.v1";
    private static final Logger log = LoggerFactory.getLogger(RxGateBlockRecorder.class);

    private final RxGateRepository repo;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final EventPublisher eventPublisher;

    private final ActorDirectory actors;

    RxGateBlockRecorder(RxGateRepository repo, TenantContext tenantContext, AuditService auditService, EventPublisher eventPublisher, ActorDirectory actors) {
        this.actors = actors;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, UUID staffId, String ip, String chargeCode, UUID itemId, String reason) {
        tenantContext.set(tenantId);
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        auditService.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "pharmacy.rx_gate_blocked", "charge_catalogue", itemId,
                null, Map.of("itemCode", chargeCode, "reason", reason));
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("itemId", itemId);
        payload.put("reason", reason);
        payload.put("actorId", staffId);
        eventPublisher.publish(tenantId, EVENT, payload);
        log.info("rx gate blocked item {} by {} (tenant {})", itemId, staffId, tenantId);
    }
}
