package com.nabd.hms.platform.fleet.dto;

import java.util.List;
import java.util.Map;

/** Powers the Fleet page's KPI tiles — counts only, same "real data or omit" rule as TenantSummary.
 * live = tenants whose status is 'active' (paying, past trial); trialing is counted separately (LIVE-07). */
public record FleetSummaryResponse(int total, int live, int trialing, Map<String, Integer> byStatus, List<String> regions) {
}
