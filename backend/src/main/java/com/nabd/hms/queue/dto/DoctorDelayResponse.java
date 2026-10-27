package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DoctorDelayResponse(UUID id, UUID doctorId, int delayMinutes, String reason, UUID announcedBy,
                                   Instant announcedAt, UUID clearedBy, Instant clearedAt, boolean active,
                                   LocalDate clinicDate, UUID delayId, Instant expiresAt, Integer recipients) {

    public DoctorDelayResponse withRecipients(int n) {
        return new DoctorDelayResponse(id, doctorId, delayMinutes, reason, announcedBy, announcedAt, clearedBy, clearedAt, active,
                clinicDate, delayId, expiresAt, n);
    }
}
