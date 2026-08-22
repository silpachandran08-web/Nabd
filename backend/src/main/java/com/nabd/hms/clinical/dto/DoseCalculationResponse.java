package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;

public record DoseCalculationResponse(BigDecimal weightKg, BigDecimal mgPerKg, BigDecimal totalDoseMg, String ruleSource) {
}
