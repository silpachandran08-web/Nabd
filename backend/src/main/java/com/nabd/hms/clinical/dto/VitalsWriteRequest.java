package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

// Every field optional (whoever is taking vitals may not have every instrument to hand), but VitalsService
// rejects a request with no measurement at all. A null field means "leave the stored value unchanged" (PATCH);
// a measurement is corrected by sending a new value, never by silently blanking it (V2-008).
// The bounds are PLAUSIBILITY limits, values that are physiologically impossible and almost certainly a typing
// error (temp 370, SpO2 150). They are deliberately wide at the low end so that genuine neonatal, preterm,
// shock, cyanotic and hypothermic readings can be recorded (V1-011): they are not age-banded and not clinical
// normal ranges (see VitalsRanges for the abnormal flags), and they never alter a value: out of bounds is a 400.
// The numbers mirror the VITAL_FIELDS table in frontend/apps/staff-web/app/nursing/page.tsx and need clinical sign-off (NB-321).
public record VitalsWriteRequest(
        @DecimalMin("20") @DecimalMax("250") BigDecimal heightCm,
        @DecimalMin("0.2") @DecimalMax("400") BigDecimal weightKg,
        @Min(20) @Max(300) Integer bpSystolic,
        @Min(10) @Max(200) Integer bpDiastolic,
        @Min(20) @Max(300) Integer pulseBpm,
        @DecimalMin("20") @DecimalMax("45") BigDecimal tempCelsius,
        @Min(1) @Max(100) Integer spo2Percent,
        // NUR-021: bloodSugar is only bounded to positive / the column width (no clinical range, unit mg/dL is a UI constant,
        // needs clinical sign-off); painScore is the 0-10 numeric rating scale.
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("9999.99") BigDecimal bloodSugar,
        @Min(0) @Max(10) Integer painScore) {
}
