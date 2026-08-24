package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;

// Every field optional — whoever's taking vitals may not have every instrument to hand.
public record VitalsWriteRequest(BigDecimal heightCm, BigDecimal weightKg, Integer bpSystolic, Integer bpDiastolic,
                                  Integer pulseBpm, BigDecimal tempCelsius, Integer spo2Percent) {
}
