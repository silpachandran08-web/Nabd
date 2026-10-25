package com.nabd.hms.platform.plans.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanResponse(UUID id, String code, String name, int monthlyPriceCents, String currency,
                            int seatLimit, boolean active, Instant createdAt,
                            int version, String market, String pricingBasis, Integer branchLimit, Integer visitsPerMonth,
                            String onTrialExpiry, List<String> modules) {
}
