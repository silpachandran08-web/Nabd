package com.nabd.hms.common;

import java.util.UUID;

/**
 * ADM-004/011: lets organization (go live) and the trial job move the BOUND tenant through the audited lifecycle without importing
 * platform. Both callers run inside a tenant transaction (request, or TenantScope.run).
 */
public interface TenantLifecyclePort {

    /** staffId = the clinic staff member who caused it, or null for the system. 400 invalid-lifecycle-transition when the move is not allowed. */
    void transitionBy(String toStatus, UUID staffId, String reason);

    /** Go live: stamps setup_completed_at (kept if already set) and go_live_by on the bound tenant. */
    void markLive(UUID staffId);
}
