package com.nabd.hms.billing.dto;

import java.math.BigDecimal;

public record LineItemResponse(String chargeCode, String chargeName, String category, int quantity,
                                BigDecimal unitPrice, BigDecimal taxRatePercent, BigDecimal lineTotal) {
}
