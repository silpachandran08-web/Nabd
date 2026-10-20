package com.nabd.hms.auth;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.SessionRevocation;
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
    private final SessionRevocation.OperatorLiveness operators;

    SessionLivenessChecker(AuthRepository repo, TenantContext tenantContext, SessionRevocation.OperatorLiveness operators) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.operators = operators;
    }

    /** The one revocation check for every access token (OPS-020 caches in front of this). tenantId null = platform operator. */
    @Transactional
    boolean isActive(UUID tenantId, UUID sessionId) {
        if (tenantId == null) {
            return operators.isActive(sessionId); // PLT-007: master.sessions
        }
        tenantContext.set(tenantId);
        return repo.isSessionActive(tenantId, sessionId);
    }
}
