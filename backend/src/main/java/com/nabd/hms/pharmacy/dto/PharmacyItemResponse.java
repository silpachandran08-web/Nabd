package com.nabd.hms.pharmacy.dto;

import java.math.BigDecimal;

public record PharmacyItemResponse(
        String id,
        String code,
        String name,
        boolean isRx,
        String hsnCode,
        BigDecimal price,
        BigDecimal taxRatePercent,
        int stockQty,
        boolean active
) {
}
