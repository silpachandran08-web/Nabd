package com.nabd.hms.platform.fleet.dto;

import java.util.List;
import java.util.Map;

/** Powers the Fleet page's KPI tiles — counts only, same "real data or omit" rule as TenantSummary. */
public record FleetSummaryResponse(int total, Map<String, Integer> byStatus, List<String> regions) {
}
