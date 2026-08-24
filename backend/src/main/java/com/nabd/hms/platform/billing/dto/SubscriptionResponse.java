package com.nabd.hms.platform.billing.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SubscriptionResponse(UUID id, UUID tenantId, String tenantName, String tenantSlug, String region,
                                    String tenantStatus, UUID planId, String planCode, String planName,
                                    int mrrCents, String currency, LocalDate renewalDate,
                                    int seatLimit, int seatsUsed, Instant createdAt) {
}
