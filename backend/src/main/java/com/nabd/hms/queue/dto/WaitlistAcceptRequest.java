package com.nabd.hms.queue.dto;

import java.time.Instant;

/**
 * REC-028: optional body for POST /v1/waitlist/{id}/accept. Absent (or slotStart null) = accept the slot
 * this entry was offered, exactly as before. slotStart set = the front desk books this waitlisted patient
 * into a slot they chose (e.g. agreed on the phone); it is checked like a normal booking and a taken slot is 409.
 */
public record WaitlistAcceptRequest(Instant slotStart) {
}
