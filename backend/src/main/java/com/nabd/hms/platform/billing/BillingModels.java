package com.nabd.hms.platform.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class BillingModels {

    private BillingModels() {
    }

    record SubscriptionRow(UUID id, UUID tenantId, String tenantName, String tenantSlug, String region,
                            String tenantStatus, UUID planId, String planCode, String planName, int mrrCents,
                            String currency, LocalDate renewalDate, int seatLimit, int seatsUsed, Instant createdAt) {
    }

    record DiscountRow(UUID id, UUID tenantId, String tenantName, UUID requestedBy, String requestedByName,
                        BigDecimal percent, String reason, String status, UUID reviewedBy, String reviewedByName,
                        Instant reviewedAt, Instant createdAt) {
    }
}
