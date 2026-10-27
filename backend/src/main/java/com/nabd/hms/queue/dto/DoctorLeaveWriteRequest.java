package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/** ACC-005: startTime and endTime are optional and come together; with them the leave covers part of one clinic day. */
public record DoctorLeaveWriteRequest(@NotNull LocalDate dateFrom, @NotNull LocalDate dateTo, String reason,
                                      LocalTime startTime, LocalTime endTime) {
}
