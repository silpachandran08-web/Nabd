package com.nabd.hms.patient.registry;

import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * OPS-007: keeps patient_registry_summary current. DB triggers (V221) queue the patient in registry_dirty inside the
 * writer's transaction; this drains the queue a second later, so a committed change shows in well under 5 s.
 * When the Streams relay exists, its 'projector' consumer calls {@link #project} instead of {@link #drain}.
 * shortcut: polling queue, not after-commit Modulith listeners (not in this repo), swap when the relay lands.
 */
@Component
public class RegistryProjector {

    /** Published once per patient whose summary really changed (payload as in patient.registry_summary.updated.v1). */
    public record SummaryUpdated(UUID tenantId, UUID patientId, long changeSeq) {
        public static final String EVENT = "patient.registry_summary.updated.v1";
    }

    private static final Logger log = LoggerFactory.getLogger(RegistryProjector.class);
    private static final int BATCH = 500;

    private final RegistrySummaryRepository repo;
    private final TenantContext tenantContext;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    RegistryProjector(RegistrySummaryRepository repo, TenantContext tenantContext, PlatformTransactionManager txManager,
                      ApplicationEventPublisher events) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.tx = new TransactionTemplate(txManager);
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${app.registry.poll-interval-ms:1000}")
    public void drain() {
        try {
            for (UUID tenantId : tx.execute(s -> repo.dirtyTenants(100))) {
                int claimed;
                do {
                    claimed = tx.execute(s -> {
                        tenantContext.set(tenantId);
                        List<UUID> ids = repo.claimDirty(tenantId, BATCH);
                        publish(tenantId, repo.upsert(tenantId, ids));
                        return ids.size();
                    });
                } while (claimed == BATCH);
            }
        } catch (RuntimeException e) {
            // rows stay queued (same transaction), the next poll retries; reconciliation is the backstop
            log.warn("registry projector pass failed: {}", e.toString());
        }
    }

    /** Idempotent recompute of the given patients (replaying a change twice yields the same row). Runs in the caller's tenant transaction. */
    public int project(UUID tenantId, List<UUID> patientIds) {
        return publish(tenantId, repo.upsert(tenantId, patientIds));
    }

    private int publish(UUID tenantId, List<RegistrySummaryRepository.Changed> changed) {
        changed.forEach(c -> events.publishEvent(new SummaryUpdated(tenantId, c.patientId(), c.changeSeq())));
        return changed.size();
    }
}
