package com.nabd.hms.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PLT-007: the session-revocation seams between iam (auth, owns public.sessions) and platform (owns master.sessions
 * and tenants). Both sides depend only on this kernel file, so neither module imports the other.
 */
public final class SessionRevocation {

    /** Published on every revocation; payload {tenantId, staffId, sessionIds[] | sessionCount, reason}. */
    public static final String EVENT = "iam.session.revoked.v1";

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

    public static Map<String, Object> payload(UUID tenantId, UUID staffId, List<UUID> sessionIds, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tenantId", tenantId.toString());
        p.put("staffId", staffId == null ? null : staffId.toString());
        if (staffId == null) {
            p.put("sessionCount", sessionIds.size()); // tenant-wide: ids would blow the event size budget
        } else {
            p.put("sessionIds", sessionIds.stream().map(UUID::toString).toList());
        }
        p.put("reason", reason);
        return p;
    }
}
