package com.nabd.hms.common;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * PLT-007: the session-revocation seams between iam (auth, owns public.sessions) and platform (owns master.sessions
 * and tenants). Both sides depend only on this kernel file, so neither module imports the other.
 */
public final class SessionRevocation {

    /** Tenant statuses whose start ends every live session (TenantLifecycleService revokes on entering one). */
    public static final Set<String> INACTIVE_TENANT_STATUSES = Set.of("suspended", "offboarded");

    /**
     * ADM-020: the one status that signs in nobody and refuses refresh / workspace select. A suspended clinic is read-only
     * instead (platform's SuspendedTenantWriteBlock answers 423 on writes), so its staff sign in again after the revoke.
     */
    public static final String CLOSED_TENANT_STATUS = "offboarded";

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
