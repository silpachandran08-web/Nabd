package com.nabd.hms.queue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

final class QueueModels {
    private QueueModels() {
    }

    record WorkingHoursRow(UUID id, UUID doctorId, int dayOfWeek, LocalTime startTime, LocalTime endTime,
                            int slotMinutes, Integer maxPatients) {
    }

    record DoctorLeaveRow(UUID id, UUID doctorId, LocalDate dateFrom, LocalDate dateTo, String reason) {
    }

    record AppointmentRow(UUID id, UUID tenantId, UUID patientId, UUID doctorId, Instant startTime,
                           Instant endTime, String status, Instant createdAt) {
    }

    record QueueEntryRow(UUID id, UUID appointmentId, UUID patientId, UUID doctorId, LocalDate queueDate,
                          int tokenNumber, String status, boolean priority, String priorityReason, Instant createdAt) {
    }
}
