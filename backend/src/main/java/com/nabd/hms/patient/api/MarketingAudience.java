package com.nabd.hms.patient.api;

import java.util.List;
import java.util.UUID;

/**
 * IDC-008: the only way a campaign, a package upsell or a referral reward may pick patients to target. The filter is
 * server-side and fixed: active patients with a known date of birth that makes them 18 or older today in the clinic's
 * time zone, a granted marketing consent and a granted processing consent. Under-18 profiles never appear, whatever the caller asks.
 * Runs in the caller's tenant transaction.
 */
public interface MarketingAudience {

    /** Patient ids after {@code afterId} (keyset, null = from the start), at most {@code limit} (1-1000). */
    List<UUID> adults(UUID afterId, int limit);
}
