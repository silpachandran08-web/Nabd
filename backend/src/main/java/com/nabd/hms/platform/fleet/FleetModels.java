package com.nabd.hms.platform.fleet;

import java.time.Instant;
import java.util.UUID;

final class FleetModels {

    private FleetModels() {
    }

    /**
     * Only fields with a real data source today: region and status come straight off tenants;
     * brand/owner identify who runs it. Plan, usage, and health/incident flags belong here per the
     * spec but have no backing data yet (pricing/packaging is NB-269, usage metering is NB-273,
     * platform health monitoring is NB-263/264) — omitted rather than faked with placeholder values.
     */
    record TenantSummary(UUID id, String slug, String name, String region, String status,
                          String brandName, String ownerName, String ownerEmail, Instant createdAt) {
    }
}
