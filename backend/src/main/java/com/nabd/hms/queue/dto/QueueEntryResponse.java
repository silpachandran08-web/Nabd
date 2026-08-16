package com.nabd.hms.queue.dto;

import java.time.LocalDate;
import java.util.UUID;

public record QueueEntryResponse(UUID id, UUID appointmentId, UUID patientId, UUID doctorId, LocalDate queueDate,
                                  int tokenNumber, String status, boolean priority, String priorityReason) {
}
