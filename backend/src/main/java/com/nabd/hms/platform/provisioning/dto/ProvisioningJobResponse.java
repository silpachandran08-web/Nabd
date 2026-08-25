package com.nabd.hms.platform.provisioning.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProvisioningJobResponse(
        UUID id,
        String tenantSlug,
        String tenantName,
        String region,
        String status,
        String path,
        Instant approvedAt,
        UUID createdTenantId,
        List<ProvisioningStepResponse> steps,
        /** Reveal-once: only non-null on the advance() response right after verify_invite_owner runs. */
        String ownerInviteToken
) {
}
