package com.nabd.hms.queue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

final class QueueModels {
    private QueueModels() {
    }

    record WorkingHoursRow(UUID id, UUID doctorId, int dayOfWeek, LocalTime startTime, LocalTime endTime,
                            int slotMinutes, Integer maxPatients, String sessionMode, String previousSessionMode,
                            Integer onlineCap, String label, LocalDate effectiveFrom,
                            String walkinMode, int walkinRatio, int lateGraceMinutes) {
        /** ACC-005: end_time before start_time = the block runs past midnight, into the next calendar day of the same clinic day. */
        public boolean endsNextDay() {
            return !endTime.isAfter(startTime);
        }

        /** Seconds from the local midnight of the clinic date; an overnight block ends after 86400. */
        public int startSecond() {
            return startTime.toSecondOfDay();
        }

        public int endSecond() {
            return endTime.toSecondOfDay() + (endsNextDay() ? 86400 : 0);
        }

        public int startMinute() {
            return startSecond() / 60;
        }

        public int endMinute() {
            return endSecond() / 60;
        }
    }

    /** startTime/endTime both null = the whole day(s); both set = part of one clinic day (end before start runs past midnight). */
    record DoctorLeaveRow(UUID id, UUID doctorId, LocalDate dateFrom, LocalDate dateTo, String reason,
                          LocalTime startTime, LocalTime endTime) {
        int[] window(int dayStartSecond) {
            return leaveWindow(startTime, endTime, dayStartSecond);
        }
    }

    /** A leave window in seconds from the clinic date's local midnight; a start before the day start is the after-midnight part of the clinic day. */
    static int[] leaveWindow(LocalTime start, LocalTime end, int dayStartSecond) {
        int s = start.toSecondOfDay();
        int e = end.toSecondOfDay();
        if (s < dayStartSecond) {
            s += 86400;
        }
        if (e <= s) {
            e += 86400;
        }
        return new int[]{s, e};
    }

    record AppointmentRow(UUID id, UUID tenantId, UUID patientId, UUID doctorId, Instant startTime,
                           Instant endTime, String status, Instant createdAt, boolean isFollowUp, String cancelReason,
                           String decisionSource, String channel, String bookingKind, Integer sessionToken,
                           String cancelReasonCode, Instant noShowAt, UUID noShowMarkedBy, UUID lateArrivalQueueEntryId, Instant patientConfirmedAt,
                           String confirmationState) {
    }

    /** NB-116: a follow-up appointment that was missed — no_show, or scheduled 15+ days past its start. */
    record CallbackEntryRow(UUID id, UUID patientId, String patientName, UUID doctorId, Instant startTime, String status) {
    }

    record QueueEntryRow(UUID id, UUID appointmentId, UUID patientId, UUID doctorId, UUID departmentId,
                          UUID parentQueueEntryId, UUID encounterId, String encounterClass, String currentStage,
                          UUID workflowDefinitionId, LocalDate queueDate, int tokenNumber, String status,
                          boolean priority, String priorityReason, UUID priorityFlaggedBy, Instant priorityFlaggedAt,
                          UUID priorityAcknowledgedBy, Instant priorityAcknowledgedAt, String source,
                          String presentingComplaint, String paymentMode, Instant createdAt,
                          String transferReason, String transferReasonNote, Integer ageYears, String gender,
                          String doctorName, int waitMinutes, String visitType, String appointmentStatus, String orderReason) {
    }

    /** NB-099: a waitlist membership; offeredSlotStart/offerExpiresAt are set only while status='offered'. */
    record WaitlistEntryRow(UUID id, UUID doctorId, UUID patientId, String patientName, Instant joinedAt, String status,
                             Instant offeredSlotStart, Instant offerExpiresAt, UUID bookedAppointmentId) {
    }

    /** NB-100: one entry in a doctor's delay history — active while clearedAt is null. */
    record DoctorDelayRow(UUID id, UUID doctorId, int delayMinutes, String reason, UUID announcedBy, Instant announcedAt,
                           UUID clearedBy, Instant clearedAt, java.time.LocalDate clinicDate,
                           Instant expiresAt, String reasonCode, boolean broadcast) {
        /** ACC-024: a delay lapses at its session end even if nobody clears it. */
        boolean active(java.time.LocalDate today, Instant now) {
            return clearedAt == null && clinicDate.equals(today) && expiresAt.isAfter(now);
        }
    }
}
