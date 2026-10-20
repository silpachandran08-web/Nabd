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
        Instant createdAt,
        // PLT-005: privileged identities (Owner role, mfa_required, approve/refundDiscount) and how many have MFA; target 100%
        int privilegedUsers,
        int privilegedMfaEnrolled,
        Integer privilegedMfaPercent // null when the clinic has no privileged users yet
) {
}
