package com.nabd.hms.queue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** startTime/endTime are null for a whole-day leave (ACC-005); affectedAppointments is filled when the leave is added. */
public record DoctorLeaveResponse(UUID id, LocalDate dateFrom, LocalDate dateTo, String reason,
                                   List<String> affectedPackagePatients, LocalTime startTime, LocalTime endTime,
                                   List<AffectedAppointment> affectedAppointments) {

    @Schema(name = "LeaveAffectedAppointment")
    public record AffectedAppointment(UUID id, Instant start) {
    }
}
