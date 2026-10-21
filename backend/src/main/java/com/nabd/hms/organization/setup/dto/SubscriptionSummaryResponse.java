package com.nabd.hms.organization.setup.dto;

public record SubscriptionSummaryResponse(
        String plan,
        String status,
        long activePatientsUsed,
        long activePatientsLimit,
        long whatsappMessagesUsed,
        long whatsappMessagesLimit,
        long branchesUsed,
        long branchesLimit
) {
}
