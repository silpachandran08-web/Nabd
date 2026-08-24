package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

public record LiabilityResponse(
        long activePatientPackages, long sessionsOwed, BigDecimal remainingListValue,
        BigDecimal remainingAllocatedValue, long inGracePeriod, long expiringIn30Days,
        BigDecimal potentialExpiryLoss, long refundsAwaitingApproval
) {
}
