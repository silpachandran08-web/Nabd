package com.nabd.hms.common;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/** PLT-019: the one way for workers and system jobs (outbox handlers, schedulers, token checks) to act for a tenant.
 * Runs the work in a transaction bound to tenantId: a new one, or the caller's if it is bound to the same tenant
 * (a caller bound to another tenant gets TenantMismatchException). */
@Component
public class TenantScope {

    private final TransactionTemplate tx;
    private final TenantContext tenantContext;

    TenantScope(PlatformTransactionManager txManager, TenantContext tenantContext) {
        this.tx = new TransactionTemplate(txManager);
        this.tenantContext = tenantContext;
    }

    public <T> T run(UUID tenantId, Supplier<T> work) {
        return tx.execute(s -> {
            tenantContext.set(tenantId);
            return work.get();
        });
    }

    public void run(UUID tenantId, Runnable work) {
        run(tenantId, () -> {
            work.run();
            return null;
        });
    }
}
