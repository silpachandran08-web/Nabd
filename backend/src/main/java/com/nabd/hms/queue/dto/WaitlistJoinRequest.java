package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WaitlistJoinRequest(@NotNull UUID doctorId, @NotNull UUID patientId) {
}
