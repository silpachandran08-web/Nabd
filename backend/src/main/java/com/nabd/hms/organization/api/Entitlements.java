package com.nabd.hms.organization.api;

import java.util.Map;

/**
 * PLT-059 / ADM-034: what the calling clinic's plan, per-clinic overrides and global kill switches allow. Answers for the
 * clinic of the current request (JWT tenantId) from an in-memory snapshot (60 s TTL, evicted on change). A clinic with
 * no subscription, or on a plan that predates entitlements, is unrestricted: nobody loses a feature by this rollout.
 * Module codes are the closed list owned by ADM-034 (plus the kill-only feature whatsapp_bot).
 */
public interface Entitlements {

    /** True when the module may be used now: in the plan (or switched on) and not killed. */
    boolean has(String module);

    /** True when a kill switch (global or for this clinic) is active for the feature: the answer is 503 FEATURE_DISABLED. */
    boolean killed(String feature);

    /** Staff seats the plan allows (invited + active staff count, suspended do not); {@link Integer#MAX_VALUE} when unlimited. */
    int seatLimit();

    /** Branches (clinics under one brand) the plan allows; {@link Integer#MAX_VALUE} when unlimited. */
    int branchLimit();

    /** Every known feature code with its current effective state, for navigation gating and the plan screen. */
    Map<String, Boolean> modules();
}
