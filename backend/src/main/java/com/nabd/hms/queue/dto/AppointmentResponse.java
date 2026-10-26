package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.util.UUID;

/** bookingKind/sessionToken/expectedRange: ACC-011 token bookings (expectedRange like "17:40-18:00", never a fixed time). */
public record AppointmentResponse(UUID id, UUID patientId, UUID doctorId, Instant startTime, Instant endTime,
                                   String status, boolean isFollowUp, String cancelReason, String decisionSource, String channel,
                                   String bookingKind, Integer sessionToken, String expectedRange) {
}
