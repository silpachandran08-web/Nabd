package com.nabd.hms.platform.plans.dto;

import java.time.Instant;
import java.util.UUID;

public record PlanResponse(UUID id, String code, String name, int monthlyPriceCents, String currency,
                            int seatLimit, boolean active, Instant createdAt) {
}
