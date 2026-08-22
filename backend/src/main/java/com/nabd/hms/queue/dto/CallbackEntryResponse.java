package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.util.UUID;

public record CallbackEntryResponse(UUID appointmentId, UUID patientId, String patientName, UUID doctorId,
                                     Instant startTime, String status) {
}
