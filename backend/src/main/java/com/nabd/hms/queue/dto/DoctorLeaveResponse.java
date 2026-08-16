package com.nabd.hms.queue.dto;

import java.time.LocalDate;
import java.util.UUID;

public record DoctorLeaveResponse(UUID id, LocalDate dateFrom, LocalDate dateTo, String reason) {
}
