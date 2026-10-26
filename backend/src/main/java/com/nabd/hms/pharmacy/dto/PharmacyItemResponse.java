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
        boolean active,
        String drugId,
        /** PHL-002: statutory class code (OTC, IN_H1, ...). */
        String scheduleClass,
        /** PHL-002: set when the class needs a statutory register (the badge PHL-022/024 use); null otherwise. */
        String register,
        String genericName,
        Integer packSize,
        String unit,
        /** PHL-004: false = not dispensed here, shown as "buy outside" on the Rx and never offered at checkout. */
        boolean inHouse
) {
}
