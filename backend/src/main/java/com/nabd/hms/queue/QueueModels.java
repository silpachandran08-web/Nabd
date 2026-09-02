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
                           Instant endTime, String status, Instant createdAt, boolean isFollowUp) {
    }

    /** NB-116: a follow-up appointment that was missed — no_show, or scheduled 15+ days past its start. */
    record CallbackEntryRow(UUID id, UUID patientId, String patientName, UUID doctorId, Instant startTime, String status) {
    }

    record QueueEntryRow(UUID id, UUID appointmentId, UUID patientId, UUID doctorId, UUID departmentId,
                          UUID parentQueueEntryId, LocalDate queueDate, int tokenNumber, String status,
                          boolean priority, String priorityReason, UUID priorityFlaggedBy, Instant priorityFlaggedAt,
                          UUID priorityAcknowledgedBy, Instant priorityAcknowledgedAt, String source, Instant createdAt) {
    }

    /** NB-099: a waitlist membership; offeredSlotStart/offerExpiresAt are set only while status='offered'. */
    record WaitlistEntryRow(UUID id, UUID doctorId, UUID patientId, String patientName, Instant joinedAt, String status,
                             Instant offeredSlotStart, Instant offerExpiresAt, UUID bookedAppointmentId) {
    }

    /** NB-100: one entry in a doctor's delay history — active while clearedAt is null. */
    record DoctorDelayRow(UUID id, UUID doctorId, int delayMinutes, String reason, UUID announcedBy, Instant announcedAt,
                           UUID clearedBy, Instant clearedAt) {
        boolean active() {
            return clearedAt == null;
        }
    }
}
