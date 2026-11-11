package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.util.UUID;

/** ACC-041: patientConfirmedAt (the patient tapped Confirm on the reminder). ACC-007: cancelReasonCode (cancelReason is the free-text note). ACC-025: noShowAt/noShowMarkedBy (null = system)/lateArrivalQueueEntryId. bookingKind/sessionToken/expectedRange: ACC-011 token bookings (expectedRange like "17:40-18:00", never a fixed time). */
public record AppointmentResponse(UUID id, UUID patientId, UUID doctorId, Instant startTime, Instant endTime,
                                   String status, boolean isFollowUp, String cancelReason, String decisionSource, String channel,
                                   String bookingKind, Integer sessionToken, String expectedRange,
                                   String cancelReasonCode, Instant noShowAt, UUID noShowMarkedBy, UUID lateArrivalQueueEntryId, Instant patientConfirmedAt,
                                   /** COM-043: none | nudged | confirmed | unconfirmed_flagged (the reminder cascade). */
                                   String confirmationState) {
}
