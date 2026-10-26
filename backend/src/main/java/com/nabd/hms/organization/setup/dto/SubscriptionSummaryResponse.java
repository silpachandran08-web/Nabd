package com.nabd.hms.organization.setup.dto;

public record SubscriptionSummaryResponse(
        String plan,
        String status,
        long activePatientsUsed,
        long activePatientsLimit,
        long whatsappMessagesUsed,
        long whatsappMessagesLimit,
        long branchesUsed,
        long branchesLimit,
        // PLT-059: plan entitlements and seat usage (additive)
        java.util.List<ModuleState> modules,
        java.util.List<String> killedFeatures,
        long seatsUsed,
        Long seatsLimit
) {
    public record ModuleState(String code, boolean enabled) {
    }
}
