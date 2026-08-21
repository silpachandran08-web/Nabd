package com.nabd.hms.setup.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ChargeHeadResponse(
        String id,
        String code,
        String name,
        String category,
        BigDecimal baseAmount,
        BigDecimal followUpAmount,
        BigDecimal emergencyAmount,
        String taxCode,
        BigDecimal taxRatePercent,
        boolean doctorOverride,
        boolean active,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int displayOrder
) {
}
