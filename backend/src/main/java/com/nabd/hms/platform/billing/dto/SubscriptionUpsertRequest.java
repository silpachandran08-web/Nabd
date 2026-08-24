package com.nabd.hms.platform.billing.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

// Currency is deliberately not a field here — it's derived server-side from the tenant's region
// (IN -> INR, KSA -> SAR), one less thing an operator can set wrong.
public record SubscriptionUpsertRequest(
        @NotNull UUID planId,
        @NotNull @Min(0) Integer mrrCents,
        @NotNull @Future LocalDate renewalDate
) {
}
