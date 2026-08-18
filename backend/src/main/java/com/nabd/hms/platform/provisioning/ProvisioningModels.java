package com.nabd.hms.platform.provisioning;

import java.time.Instant;
import java.util.UUID;

final class ProvisioningModels {

    private ProvisioningModels() {
    }

    record Job(UUID id, UUID requestedBy, String tenantSlug, String tenantName, String region,
               String ownerEmail, String ownerName, String brandName, String status,
               UUID createdTenantId, UUID createdOwnerId, UUID createdBrandId,
               boolean ownerNewlyCreated, boolean brandNewlyCreated) {
    }

    record JobStep(UUID id, UUID jobId, String stepName, int stepOrder, String status,
                   Instant startedAt, Instant completedAt, String errorDetail) {
    }
}
