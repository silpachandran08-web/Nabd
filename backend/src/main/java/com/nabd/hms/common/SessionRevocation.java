package com.nabd.hms.common;

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

    /** Implemented by iam: revoke every live staff session of a tenant in one UPDATE; returns the count. */
    public interface TenantRevoker {
        int revokeAll(UUID tenantId, String reason);
    }
}
