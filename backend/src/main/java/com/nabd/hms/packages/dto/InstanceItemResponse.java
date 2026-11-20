package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

/** FIN-025: allocatedMinor (taxable value), taxMinor and taxCategory (TAXABLE / EXEMPT / NIL) are the component's invoice line. */
public record InstanceItemResponse(
        String id, String itemType, String name, int quantityTotal, int quantityConsumed,
        BigDecimal unitListPrice, BigDecimal allocatedPrice, BigDecimal taxRatePercent,
        long allocatedMinor, long taxMinor, String taxCategory
) {
}
