package com.nabd.hms.auth;

import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A separate bean (not a method on SessionRevocationJwtDecoder) on purpose: @Transactional only
 * works through the Spring AOP proxy, and a decoder calling its own method via `this` would bypass
 * that proxy entirely (self-invocation), silently running the RLS-scoped query with no transaction
 * — see the "SET LOCAL app.tenant_id never took effect" failure mode this sidesteps.
 */
@Service
class SessionLivenessChecker {

    private final AuthRepository repo;
    private final TenantContext tenantContext;

    SessionLivenessChecker(AuthRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    boolean isActive(UUID tenantId, UUID sessionId) {
        tenantContext.set(tenantId);
        return repo.isSessionActive(tenantId, sessionId);
    }
}
