package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

/** chargeCatalogueId (FIN-025): the linked price-list head, null while the component is not linked to one. */
public record PackageItemResponse(
        String id, String itemType, String name, int quantity, BigDecimal unitListPrice,
        BigDecimal taxRatePercent, BigDecimal listValue, String chargeCatalogueId
) {
}
