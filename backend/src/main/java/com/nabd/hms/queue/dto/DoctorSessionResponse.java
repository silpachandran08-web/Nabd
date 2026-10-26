package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.util.UUID;

/** sessionId and since are null when the doctor has no session today (state "out"). */
public record DoctorSessionResponse(UUID sessionId, String state, Instant since) {
}
