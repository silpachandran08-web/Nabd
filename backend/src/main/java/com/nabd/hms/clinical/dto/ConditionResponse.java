package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ConditionResponse(UUID id, UUID patientId, String condition, String status, LocalDate reviewDueDate,
                                 UUID recordedBy, Instant recordedAt) {
}
