package com.nabd.hms.platform.tenant.dto;

import java.util.UUID;

public record TenantRegionResponse(UUID tenantId, String regionCode) {
}
