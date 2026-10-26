package com.nabd.hms.queue.dto;

/** reason: priority | booked_on_time | interleave | reserved_slot. */
public record CallNextResponse(QueueEntryResponse entry, String reason) {
}
