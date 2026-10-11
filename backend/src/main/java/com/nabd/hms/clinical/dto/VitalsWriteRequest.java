package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

// Every field optional (whoever is taking vitals may not have every instrument to hand), but VitalsService
// rejects a request with no measurement at all. The bounds are PLAUSIBILITY limits, values that are
// physiologically impossible and almost certainly a typing error (temp 370, SpO2 150). They are not clinical
// normal ranges (see VitalsRanges for the abnormal flags) and never alter a value: out of bounds is a 400.
// They mirror the nursing screen's hard limits and need clinical sign-off before go-live (NB-321).
public record VitalsWriteRequest(
        @DecimalMin("30") @DecimalMax("250") BigDecimal heightCm,
        @DecimalMin("0.3") @DecimalMax("400") BigDecimal weightKg,
        @Min(40) @Max(300) Integer bpSystolic,
        @Min(20) @Max(200) Integer bpDiastolic,
        @Min(20) @Max(300) Integer pulseBpm,
        @DecimalMin("25") @DecimalMax("45") BigDecimal tempCelsius,
        @Min(50) @Max(100) Integer spo2Percent) {
}
