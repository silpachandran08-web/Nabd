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
        /** Reveal-once, per-tenant staff invite: only non-null on the advance() response right after
         * verify_invite_owner runs. Accept at /accept-invite/{token}. */
        String ownerInviteToken,
        /** Reveal-once, top-level Owner account invite (NB-354/NB-350) — lets the same owner log into
         * every clinic they own from one place. Null if this owner already activated their account
         * (e.g. this is their second clinic). Accept at /owner/accept-invite/{token}. */
        String ownerAccountInviteToken
) {
}
