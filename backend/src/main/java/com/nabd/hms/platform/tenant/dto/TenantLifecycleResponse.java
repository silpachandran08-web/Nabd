package com.nabd.hms.platform.tenant.dto;

import java.util.List;
import java.util.UUID;

public record TenantLifecycleResponse(
        UUID tenantId,
        String status,
        List<LifecycleEventResponse> events
) {
}
