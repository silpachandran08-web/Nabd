package com.nabd.hms.platform.plans.dto;

import java.time.Instant;
import java.util.List;

public record PlanVersionResponse(int version, int monthlyPriceCents, int seatLimit, Integer branchLimit,
                                  Integer visitsPerMonth, List<String> modules, Instant publishedAt, long subscriptions) {
}
