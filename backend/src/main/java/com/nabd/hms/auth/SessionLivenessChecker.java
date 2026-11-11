package com.nabd.hms.auth;

import com.nabd.hms.auth.internal.store.AuthSessionRepository;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.SessionRevocation;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Runs before the SecurityContext exists (it is part of decoding the token), so nothing is bound at transaction
 * start: TenantScope.run (PLT-019) opens the tenant transaction explicitly — the "SET LOCAL app.tenant_id never
 * took effect" failure mode of a query outside a transaction cannot happen.
 */
@Service
class SessionLivenessChecker {

    private final AuthSessionRepository sessionRepo;
    private final TenantScope tenantScope;
    private final SessionRevocation.OperatorLiveness operators;

    SessionLivenessChecker(AuthSessionRepository sessionRepo, TenantScope tenantScope, SessionRevocation.OperatorLiveness operators) {
        this.sessionRepo = sessionRepo;
        this.tenantScope = tenantScope;
        this.operators = operators;
    }

    /** The one revocation check for every access token (OPS-020 caches in front of this). tenantId null = platform operator. */
    boolean isActive(UUID tenantId, UUID sessionId) {
        if (tenantId == null) {
            return operators.isActive(sessionId); // PLT-007: master.sessions
        }
        return tenantScope.run(tenantId, () -> sessionRepo.isSessionActive(tenantId, sessionId));
    }
}
