package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record AppointmentWriteRequest(@NotNull UUID patientId, @NotNull UUID doctorId, @NotNull Instant startTime,
                                       Boolean isFollowUp) {
    public boolean isFollowUpOrDefault() {
        return Boolean.TRUE.equals(isFollowUp);
    }
}
