package com.nabd.hms.platform.fleet.dto;

import java.time.Instant;
import java.util.UUID;

public record TenantSummaryResponse(
        UUID id,
        String slug,
        String name,
        String region,
        String status,
        String brandName,
        String ownerName,
        String ownerEmail,
        Instant createdAt
) {
}
