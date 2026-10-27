package com.nabd.hms.platform.billing.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

// Currency is deliberately not a field here — it's derived server-side from the tenant's region
// (the tenant region's currency), one less thing an operator can set wrong.
public record SubscriptionUpsertRequest(
        @NotNull UUID planId,
        @Min(0) Integer mrrCents, // ADM-034: omitted = priced from the plan's pricing basis (per_doctor: unit price x active clinicians)
        @NotNull @Future LocalDate renewalDate,
        Boolean startTrial // ADM-004: true = a trial subscription that ends after the plan's trial_days (14 by default); omitted/false = a real, active one
) {
}
