package com.nabd.hms.platform.tenant;

import java.time.Instant;
import java.util.UUID;

final class TenantLifecycleModels {

    private TenantLifecycleModels() {
    }

    record LifecycleEvent(String fromStatus, String toStatus, UUID changedBy, String reason, Instant changedAt) {
    }
}
