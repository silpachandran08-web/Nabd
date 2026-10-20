package com.nabd.hms.auth;

import com.nabd.hms.common.TenantScope;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Runs before the SecurityContext exists (it is part of decoding the token), so nothing is bound at transaction
 * start: TenantScope.run (PLT-019) opens the tenant transaction explicitly — the "SET LOCAL app.tenant_id never
 * took effect" failure mode of a query outside a transaction cannot happen.
 */
@Service
class SessionLivenessChecker {

    private final AuthRepository repo;
    private final TenantScope tenantScope;

    SessionLivenessChecker(AuthRepository repo, TenantScope tenantScope) {
        this.repo = repo;
        this.tenantScope = tenantScope;
    }

    boolean isActive(UUID tenantId, UUID sessionId) {
        return tenantScope.run(tenantId, () -> repo.isSessionActive(tenantId, sessionId));
    }
}
