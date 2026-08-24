package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

public record PackageItemResponse(
        String id, String itemType, String name, int quantity, BigDecimal unitListPrice,
        BigDecimal taxRatePercent, BigDecimal listValue
) {
}
