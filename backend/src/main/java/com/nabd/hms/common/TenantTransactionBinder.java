package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

import java.util.UUID;

/**
 * PLT-019: binds app.tenant_id / app.visible_tenant_ids from the signed-in staff token (tenantId claim) as soon as a
 * Spring-managed transaction begins, so a service that forgets TenantContext.set is still scoped. Spring Boot registers
 * TransactionExecutionListener beans on the auto-configured transaction manager. No tenant claim (operators, workers,
 * sign-in) binds nothing: those bind explicitly through TenantScope / TenantContext.set.
 */
@Component
class TenantTransactionBinder implements TransactionExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TenantTransactionBinder.class);

    private final TenantContext tenantContext;

    TenantTransactionBinder(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        UUID tenant = beginFailure == null ? signedInTenant() : null;
        if (tenant == null) {
            return;
        }
        try {
            tenantContext.set(tenant);
        } catch (RuntimeException e) {
            // Throwing here would leave the begun transaction unfinished (leaked connection): fail it at commit instead.
            log.error("could not bind tenant {} at transaction start; the transaction will roll back", tenant, e);
            tenantContext.rollbackOnly();
        }
    }

    static UUID signedInTenant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        String claim = a != null && a.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("tenantId") : null;
        return claim == null ? null : UUID.fromString(claim);
    }
}
