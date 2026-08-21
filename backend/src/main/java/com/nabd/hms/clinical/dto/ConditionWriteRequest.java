package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record ConditionWriteRequest(@NotBlank String condition, LocalDate reviewDueDate) {
}
