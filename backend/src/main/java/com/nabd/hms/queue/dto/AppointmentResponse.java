package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.util.UUID;

public record AppointmentResponse(UUID id, UUID patientId, UUID doctorId, Instant startTime, Instant endTime,
                                   String status, boolean isFollowUp) {
}
