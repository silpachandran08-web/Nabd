package com.nabd.hms.clinical.dto;

import java.time.LocalDate;
import java.util.UUID;

public record DueConditionResponse(UUID id, UUID patientId, String patientName, String condition, LocalDate reviewDueDate) {
}
