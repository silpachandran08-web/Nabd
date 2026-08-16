package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DoctorLeaveWriteRequest(@NotNull LocalDate dateFrom, @NotNull LocalDate dateTo, String reason) {
}
