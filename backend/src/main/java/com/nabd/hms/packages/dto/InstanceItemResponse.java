package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

public record InstanceItemResponse(
        String id, String itemType, String name, int quantityTotal, int quantityConsumed,
        BigDecimal unitListPrice, BigDecimal allocatedPrice, BigDecimal taxRatePercent
) {
}
