package com.nabd.hms.common;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * PLT-007: the session-revocation seams between iam (auth, owns public.sessions) and platform (owns master.sessions
 * and tenants). Both sides depend only on this kernel file, so neither module imports the other.
 */
public final class SessionRevocation {

    /** Tenant statuses that end every session and refuse refresh / workspace select (same set login refuses). */
    public static final Set<String> INACTIVE_TENANT_STATUSES = Set.of("suspended", "offboarded");

    private SessionRevocation() {
    }

    /** Implemented by platform: is this operator session (master.sessions) still live. */
    public interface OperatorLiveness {
        boolean isActive(UUID sessionId);
    }

    /**
     * OPS-020: implemented by iam's Valkey revocation cache. Every UPDATE that sets sessions.revoked_at passes the ids
     * it revoked (RETURNING id), so every replica rejects them within a second (Ops020RevocationWriteThroughTest
     * fails a new revoke statement that does not).
     */
    public interface RevokedSessions {
        void revoked(Collection<UUID> sessionIds);
    }

    /** Implemented by iam: revoke every live staff session of a tenant in one UPDATE; returns the count. */
    public interface TenantRevoker {
        int revokeAll(UUID tenantId, String reason);
    }
}
