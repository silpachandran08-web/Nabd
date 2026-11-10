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
        Long seatsLimit,
        // ADM-037: the owner's plan page (additive; null when the clinic has no subscription yet)
        String planCode,
        Integer version,
        java.time.LocalDate renewalDate,
        String currency,
        NextInvoice nextInvoice,
        String paymentMethod,
        Usage usage,
        java.util.List<com.nabd.hms.organization.api.PlanAccount.PlanOption> availablePlans,
        com.nabd.hms.organization.api.PlanAccount.PendingChange pendingChange
) {
    public record ModuleState(String code, boolean enabled) {
    }

    public record NextInvoice(long amountMinor, String currency) {
    }

    /**
     * This calendar month. whatsappPassThroughMinor and aiChecks are null while their meters are not connected to Core
     * (Meta per-message cost lives in the comms gateway, T-365; no AI-check meter exists yet): never a made-up number.
     */
    public record Usage(long seatsUsed, Long seatsLimit, long branchesUsed, long branchesLimit, long whatsappUtilityMessages,
                        Long whatsappPassThroughMinor, Long aiChecks) {
    }
}
