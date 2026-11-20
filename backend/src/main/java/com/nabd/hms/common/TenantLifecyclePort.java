package com.nabd.hms.common;

import java.util.UUID;

/**
 * ADM-004/011: lets organization (go live) and the trial job move the BOUND tenant through the audited lifecycle without importing
 * platform. Both callers run inside a tenant transaction (request, or TenantScope.run).
 */
public interface TenantLifecyclePort {

    /** staffId = the clinic staff member who caused it, or null for the system. 409 INVALID_TRANSITION when the move is not allowed (ADM-020; a blank reason is 400). */
    void transitionBy(String toStatus, UUID staffId, String reason);

    /** Go live: stamps setup_completed_at (kept if already set) and go_live_by on the bound tenant. */
    void markLive(UUID staffId);
}
