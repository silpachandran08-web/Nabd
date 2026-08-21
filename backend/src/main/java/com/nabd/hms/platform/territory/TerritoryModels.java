package com.nabd.hms.platform.territory;

import java.util.List;

final class TerritoryModels {

    private TerritoryModels() {
    }

    record PlanMixEntry(String planCode, long tenantCount) {
    }

    /**
     * Scoped to the two real regions (IN, KSA) — tenants carry only a region, no state/city field, so
     * "Territories" here means region rollups, not state-level. Aggregator demand (the wireframe's
     * fifth column) is omitted: NB-227 (E19) hasn't shipped, so there's no demand data to show.
     */
    record RegionSummary(String region, long clinicCount, long activeClinicCount, long userCount,
                          long mrrCents, String currency, List<String> taxIdTypes,
                          List<PlanMixEntry> planMix, long newClinicsLast30d) {
    }
}
