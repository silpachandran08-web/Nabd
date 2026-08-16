package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CheckInRequest(UUID appointmentId, @NotNull UUID patientId, @NotNull UUID doctorId) {
}
