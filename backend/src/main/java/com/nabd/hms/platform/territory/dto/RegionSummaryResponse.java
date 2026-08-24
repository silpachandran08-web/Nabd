package com.nabd.hms.platform.territory.dto;

import java.util.List;

public record RegionSummaryResponse(String region, long clinicCount, long activeClinicCount, long userCount,
                                     long mrrCents, String currency, List<String> taxIdTypes,
                                     List<PlanMixResponse> planMix, long newClinicsLast30d) {
}
