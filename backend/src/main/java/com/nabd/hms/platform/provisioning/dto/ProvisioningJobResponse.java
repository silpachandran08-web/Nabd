package com.nabd.hms.platform.provisioning.dto;

import java.util.List;
import java.util.UUID;

public record ProvisioningJobResponse(
        UUID id,
        String tenantSlug,
        String tenantName,
        String region,
        String status,
        UUID createdTenantId,
        List<ProvisioningStepResponse> steps
) {
}
