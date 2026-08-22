package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.util.UUID;

public record WaitlistEntryResponse(UUID id, UUID doctorId, UUID patientId, String patientName, Instant joinedAt,
                                     String status, Instant offeredSlotStart, Instant offerExpiresAt, UUID bookedAppointmentId) {
}
