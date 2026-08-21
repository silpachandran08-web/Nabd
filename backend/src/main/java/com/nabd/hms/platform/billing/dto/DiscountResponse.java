package com.nabd.hms.platform.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DiscountResponse(UUID id, UUID tenantId, String tenantName, UUID requestedBy, String requestedByName,
                                BigDecimal percent, String reason, String status, UUID reviewedBy,
                                String reviewedByName, Instant reviewedAt, Instant createdAt) {
}
