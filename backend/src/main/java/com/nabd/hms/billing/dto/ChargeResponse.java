package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargeResponse(UUID id, String code, String name, String category, BigDecimal baseAmount,
                              BigDecimal followUpAmount, BigDecimal taxRatePercent) {
}
