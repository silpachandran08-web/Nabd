package com.nabd.hms.platform.plans;

import java.time.Instant;
import java.util.UUID;

final class PlanModels {

    private PlanModels() {
    }

    record Plan(UUID id, String code, String name, int monthlyPriceCents, String currency,
                int seatLimit, boolean active, Instant createdAt) {
    }
}
