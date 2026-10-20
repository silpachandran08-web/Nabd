package com.nabd.hms.patient.registry;

import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OPS-007: batched backfill and nightly reconciliation are the same pass: recompute every patient in keyset batches
 * (one short transaction each, so writes are never blocked) and write only rows that differ. For a fresh tenant the
 * differing rows are the backfill; afterwards they are drift, counted in registry_projection_drift_rows.
 * shortcut: no ShedLock in this repo; two instances running it at once is harmless (idempotent) but wasteful.
 * shortcut: the gauge is in-process plus a log line; a Prometheus export needs micrometer/actuator (needs an ADR).
 */
@Component
public class RegistryReconciler {

    private static final Logger log = LoggerFactory.getLogger(RegistryReconciler.class);
    private static final int BATCH = 250; // each batch statement must finish inside the nabd_app role's 5 s statement_timeout
    private static final UUID MIN_ID = new UUID(0, 0);

    private final RegistrySummaryRepository repo;
    private final RegistryProjector projector;
    private final TenantContext tenantContext;
    private final TransactionTemplate tx;
    private final AtomicLong driftRows = new AtomicLong();

    RegistryReconciler(RegistrySummaryRepository repo, RegistryProjector projector, TenantContext tenantContext,
                       PlatformTransactionManager txManager) {
        this.repo = repo;
        this.projector = projector;
        this.tenantContext = tenantContext;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Value of the registry_projection_drift_rows gauge: rows repaired by the last full run. */
    public long driftRows() {
        return driftRows.get();
    }

    @Scheduled(cron = "${app.registry.reconcile-cron:0 30 2 * * *}")
    @SchedulerLock(name = "registry-reconcile", lockAtMostFor = "PT2H")
    public void runAll() {
        long total = 0;
        for (UUID tenantId : tx.execute(s -> repo.allTenants())) {
            try {
                total += reconcileTenant(tenantId);
            } catch (RuntimeException e) {
                log.warn("registry reconcile failed for tenant {}: {}", tenantId, e.toString());
            }
        }
        driftRows.set(total);
        log.info("registry_projection_drift_rows={}", total);
    }

    /** Backfill / reconcile one tenant; returns how many rows were inserted or repaired. */
    public long reconcileTenant(UUID tenantId) {
        long changed = 0;
        UUID after = MIN_ID;
        while (true) {
            UUID cursor = after;
            List<UUID> page = tx.execute(s -> {
                tenantContext.set(tenantId);
                return repo.patientIdsAfter(tenantId, cursor, BATCH);
            });
            if (page.isEmpty()) {
                return changed;
            }
            after = page.get(page.size() - 1);
            changed += tx.execute(s -> {
                tenantContext.set(tenantId);
                return projector.project(tenantId, page);
            });
        }
    }
}
