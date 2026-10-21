package com.nabd.hms.organization.api;

/**
 * ADM-002: the one check every patient-creating path makes. Reads the bound tenant; class C config, cached in memory for
 * at most 5 s and evicted when a DPA is accepted. A tenant is 'live' only after a signed DPA and with a subscription.
 */
public interface DataGatePort {

    boolean isLive();

    /** Throws 403 DPA_REQUIRED while the tenant is demo_only. Callers skip it only for synthetic (demo) patients. */
    void requireRealData();
}
