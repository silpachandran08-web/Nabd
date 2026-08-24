package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.util.UUID;

public record DoctorDelayResponse(UUID id, UUID doctorId, int delayMinutes, String reason, UUID announcedBy,
                                   Instant announcedAt, UUID clearedBy, Instant clearedAt, boolean active) {
}
