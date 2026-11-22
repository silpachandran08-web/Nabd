package com.nabd.hms.platform.tenant.dto;

import java.util.List;
import java.util.UUID;

public record TenantLifecycleResponse(
        UUID tenantId,
        String status,
        List<LifecycleEventResponse> events,
        // ADM-020 (additive): the states this tenant may move to now, in ladder order (empty when offboarded)
        List<String> allowedTransitions,
        String tenantName,
        String tenantSlug
) {
}
