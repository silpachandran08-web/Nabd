package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VitalsResponse(UUID id, UUID queueEntryId, UUID patientId, BigDecimal heightCm, BigDecimal weightKg,
                              Integer bpSystolic, Integer bpDiastolic, Integer pulseBpm, BigDecimal tempCelsius,
                              Integer spo2Percent, UUID recordedBy, Instant recordedAt, List<String> abnormalFlags) {
}
